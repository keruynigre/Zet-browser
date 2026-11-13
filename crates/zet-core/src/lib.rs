//! Zet Browser çekirdeği: platformdan bağımsız iş mantığı.
//!
//! Windows (wry) ve Android (Kotlin WebView + JNI) kabukları bu crate'i kullanır.

pub mod adblock;

pub use crate::adblock::{AdBlocker, BlockDecision, ResourceType};
