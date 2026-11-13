//! Minimal SOCKS5 sunucu tarafı (RFC 1928): kimlik doğrulamasız, yalnızca CONNECT.
//!
//! WebView yerel proxy'ye bağlanır; hedef adres (alan adı olarak, böylece DNS
//! de Tor üzerinden çözülür) buradan okunup bağlantı fonksiyonuna verilir.

use std::future::Future;
use std::io;

use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Bağlantı hedefi.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    pub host: String,
    pub port: u16,
}

const REP_OK: u8 = 0x00;
const REP_FAILURE: u8 = 0x01;
const REP_CMD_UNSUPPORTED: u8 = 0x07;
const REP_ATYP_UNSUPPORTED: u8 = 0x08;

/// SOCKS5 el sıkışmasını yapar ve hedefi döner.
pub async fn handshake<S: AsyncRead + AsyncWrite + Unpin>(client: &mut S) -> io::Result<Target> {
    // Selamlama: VER, NMETHODS, METHODS...
    let mut head = [0u8; 2];
    client.read_exact(&mut head).await?;
    if head[0] != 5 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "SOCKS5 değil"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    client.read_exact(&mut methods).await?;
    if !methods.contains(&0) {
        client.write_all(&[5, 0xFF]).await?;
        return Err(io::Error::new(io::ErrorKind::InvalidData, "kimlik doğrulamasız yöntem yok"));
    }
    client.write_all(&[5, 0]).await?;

    // İstek: VER, CMD, RSV, ATYP, ADDR, PORT
    let mut req = [0u8; 4];
    client.read_exact(&mut req).await?;
    if req[1] != 1 {
        reply(client, REP_CMD_UNSUPPORTED).await?;
        return Err(io::Error::new(io::ErrorKind::Unsupported, "yalnızca CONNECT"));
    }
    let host = match req[3] {
        1 => {
            let mut a = [0u8; 4];
            client.read_exact(&mut a).await?;
            std::net::Ipv4Addr::from(a).to_string()
        }
        3 => {
            let len = client.read_u8().await? as usize;
            let mut name = vec![0u8; len];
            client.read_exact(&mut name).await?;
            String::from_utf8(name)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "geçersiz alan adı"))?
        }
        4 => {
            let mut a = [0u8; 16];
            client.read_exact(&mut a).await?;
            std::net::Ipv6Addr::from(a).to_string()
        }
        _ => {
            reply(client, REP_ATYP_UNSUPPORTED).await?;
            return Err(io::Error::new(io::ErrorKind::Unsupported, "adres türü"));
        }
    };
    let port = client.read_u16().await?;
    Ok(Target { host, port })
}

async fn reply<S: AsyncWrite + Unpin>(client: &mut S, code: u8) -> io::Result<()> {
    client.write_all(&[5, code, 0, 1, 0, 0, 0, 0, 0, 0]).await
}

/// Bir istemci bağlantısını baştan sona yürütür: el sıkışma, hedefe bağlanma,
/// iki yönlü veri aktarımı.
pub async fn serve<S, F, Fut, R>(mut client: S, connect: F) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
    F: FnOnce(Target) -> Fut,
    Fut: Future<Output = io::Result<R>>,
    R: AsyncRead + AsyncWrite + Unpin,
{
    let target = handshake(&mut client).await?;
    let mut remote = match connect(target).await {
        Ok(r) => r,
        Err(e) => {
            let _ = reply(&mut client, REP_FAILURE).await;
            return Err(e);
        }
    };
    reply(&mut client, REP_OK).await?;
    copy_bidirectional(&mut client, &mut remote).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    async fn client_request(stream: &mut tokio::io::DuplexStream, atyp_addr: &[u8], port: u16) -> Vec<u8> {
        stream.write_all(&[5, 1, 0]).await.unwrap();
        let mut sel = [0u8; 2];
        stream.read_exact(&mut sel).await.unwrap();
        assert_eq!(sel, [5, 0]);
        let mut req = vec![5, 1, 0];
        req.extend_from_slice(atyp_addr);
        req.extend_from_slice(&port.to_be_bytes());
        stream.write_all(&req).await.unwrap();
        let mut rep = [0u8; 10];
        stream.read_exact(&mut rep).await.unwrap();
        rep.to_vec()
    }

    #[tokio::test]
    async fn domain_connect_and_relay() {
        let (mut app, proxy_side) = duplex(4096);
        let server = tokio::spawn(async move {
            serve(proxy_side, |t: Target| async move {
                assert_eq!(t, Target { host: "example.com".into(), port: 443 });
                // Uzak taraf: gelen veriyi büyük harfe çeviren eko.
                let (ours, mut theirs) = duplex(4096);
                tokio::spawn(async move {
                    let mut buf = [0u8; 5];
                    theirs.read_exact(&mut buf).await.unwrap();
                    theirs.write_all(&buf.to_ascii_uppercase()).await.unwrap();
                });
                Ok(ours)
            })
            .await
        });
        let mut addr = vec![3, 11];
        addr.extend_from_slice(b"example.com");
        let rep = client_request(&mut app, &addr, 443).await;
        assert_eq!(rep[1], REP_OK);
        app.write_all(b"merha").await.unwrap();
        let mut out = [0u8; 5];
        app.read_exact(&mut out).await.unwrap();
        assert_eq!(&out, b"MERHA");
        drop(app);
        let _ = server.await;
    }

    #[tokio::test]
    async fn ipv4_and_ipv6_targets() {
        for (addr, expect) in [
            (vec![1, 93, 184, 216, 34], "93.184.216.34"),
            ([vec![4], vec![0; 15], vec![1]].concat(), "::1"),
        ] {
            let (mut app, proxy_side) = duplex(4096);
            let expect = expect.to_string();
            let server = tokio::spawn(async move {
                let mut s = proxy_side;
                handshake(&mut s).await
            });
            app.write_all(&[5, 1, 0]).await.unwrap();
            let mut sel = [0u8; 2];
            app.read_exact(&mut sel).await.unwrap();
            let mut req = vec![5, 1, 0];
            req.extend_from_slice(&addr);
            req.extend_from_slice(&80u16.to_be_bytes());
            app.write_all(&req).await.unwrap();
            let t = server.await.unwrap().unwrap();
            assert_eq!(t, Target { host: expect, port: 80 });
        }
    }

    #[tokio::test]
    async fn connect_failure_is_reported() {
        let (mut app, proxy_side) = duplex(4096);
        let server = tokio::spawn(serve(proxy_side, |_t: Target| async {
            Err::<tokio::io::DuplexStream, _>(io::Error::other("tor yok"))
        }));
        let rep = client_request(&mut app, &[1, 1, 2, 3, 4], 80).await;
        assert_eq!(rep[1], REP_FAILURE);
        assert!(server.await.unwrap().is_err());
    }

    #[tokio::test]
    async fn rejects_bind_command() {
        let (mut app, proxy_side) = duplex(4096);
        let server = tokio::spawn(async move {
            let mut s = proxy_side;
            handshake(&mut s).await
        });
        app.write_all(&[5, 1, 0]).await.unwrap();
        let mut sel = [0u8; 2];
        app.read_exact(&mut sel).await.unwrap();
        app.write_all(&[5, 2, 0, 1, 1, 2, 3, 4, 0, 80]).await.unwrap();
        let mut rep = [0u8; 10];
        app.read_exact(&mut rep).await.unwrap();
        assert_eq!(rep[1], REP_CMD_UNSUPPORTED);
        assert!(server.await.unwrap().is_err());
    }
}
