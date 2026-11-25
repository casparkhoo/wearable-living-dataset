#![allow(dead_code)]

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreprocessConfig {
    pub target_sample_rate_hz: Option<f64>,
    pub selected_channels: Vec<String>,
    pub fill_missing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreprocessReport {
    pub input_sample_count: usize,
    pub output_sample_count: usize,
    pub missing_values_filled: bool,
    pub notes: Vec<String>,
}

pub fn passthrough_sample_count<T>(samples: &[T]) -> PreprocessReport {
    PreprocessReport {
        input_sample_count: samples.len(),
        output_sample_count: samples.len(),
        missing_values_filled: false,
        notes: vec![
            "No preprocessing applied; raw/canonical samples remain unchanged.".to_string(),
        ],
    }
}
