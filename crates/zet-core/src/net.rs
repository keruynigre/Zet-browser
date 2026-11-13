//! Paylaşılan HTTP istemcisi.

use std::sync::OnceLock;
use std::time::Duration;

/// Bağlantı havuzunu paylaşan tek bir ureq ajanı.
pub fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::Agent::config_builder()
            .timeout_connect(Some(Duration::from_secs(15)))
            .timeout_recv_body(Some(Duration::from_secs(60)))
            .build()
            .into()
    })
}
