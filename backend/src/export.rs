//! One experiment as a self-describing dataset: a zip of CSVs (one per stream
//! per device recording, plus annotations, derived windows and live alerts),
//! a manifest describing every file, and `checksums.sha256` in the format
//! `sha256sum -c` reads, so a recipient can verify nothing changed in transit.
//!
//! Every table except the samples is exported generically from
//! `information_schema`, so a column added by a later migration is included
//! without changing this file.

use std::io::{Cursor, Write};

use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::{MySqlPool, Row};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

use crate::derived::channel_cells;

pub const FORMAT_VERSION: &str = "1.0";
const PAGE_ROWS: i64 = 5_000;

/// Columns of every sample file, before the stream's own channels.
const SAMPLE_COLUMNS: [&str; 14] = [
    "id",
    "raw_packet_id",
    "sample_index",
    "sequence_number",
    "received_timestamp_ms",
    "backend_received_timestamp_ms",
    "android_received_timestamp_ms",
    "device_timestamp_ms",
    "source_timestamp_value",
    "source_timestamp_unit",
    "configured_sample_rate_hz",
    "effective_sample_rate_hz",
    "clock_source",
    "clock_quality",
];

struct Archive {
    zip: ZipWriter<Cursor<Vec<u8>>>,
    files: Vec<Value>,
}

/// One file being written: its bytes go to the zip and the hash together.
struct FileWriter<'a> {
    archive: &'a mut Archive,
    path: String,
    hasher: Sha256,
    rows: i64,
    bytes: u64,
}

impl<'a> FileWriter<'a> {
    fn start(archive: &'a mut Archive, path: &str) -> Result<Self> {
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .compression_level(Some(3))
            .large_file(true);
        archive.zip.start_file(path, options)?;
        Ok(Self { archive, path: path.to_string(), hasher: Sha256::new(), rows: 0, bytes: 0 })
    }

    fn write(&mut self, text: &str) -> Result<()> {
        self.archive.zip.write_all(text.as_bytes())?;
        self.hasher.update(text.as_bytes());
        self.bytes += text.len() as u64;
        Ok(())
    }

    fn row(&mut self, cells: &[String]) -> Result<()> {
        let line = csv_line(cells);
        self.write(&line)?;
        self.rows += 1;
        Ok(())
    }

    fn finish(self, description: &str, extra: Value) -> Result<String> {
        let sha256 = hex(self.hasher.finalize().as_slice());
        let mut entry = json!({
            "path": self.path,
            "sha256": sha256,
            "bytes": self.bytes,
            "rows": self.rows,
            "description": description,
        });
        if let (Value::Object(entry), Value::Object(extra)) = (&mut entry, extra) {
            entry.extend(extra);
        }
        self.archive.files.push(entry);
        Ok(sha256)
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// RFC 4180: quote a cell holding a comma, quote or line break.
pub fn csv_line(cells: &[String]) -> String {
    let mut line = cells
        .iter()
        .map(|cell| {
            if cell.contains([',', '"', '\n', '\r']) {
                format!("\"{}\"", cell.replace('"', "\"\""))
            } else {
                cell.clone()
            }
        })
        .collect::<Vec<_>>()
        .join(",");
    line.push('\n');
    line
}

fn cell(value: &Value) -> String {
    match value {
        Value::Null => String::new(),
        Value::String(text) => text.clone(),
        other => other.to_string(),
    }
}

async fn table_columns(db: &MySqlPool, table: &str) -> Result<Vec<String>> {
    let columns: Vec<String> = sqlx::query_scalar(
        "SELECT CAST(column_name AS CHAR) FROM information_schema.columns \
         WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
    )
    .bind(table)
    .fetch_all(db)
    .await?;
    Ok(columns)
}

/// Rows of `table` matching `filter` (one `?`, bound to `id`), as JSON
/// objects, paged by primary key.
async fn table_rows(db: &MySqlPool, table: &str, filter: &str, id: i64) -> Result<(Vec<String>, Vec<Value>)> {
    let columns = table_columns(db, table).await?;
    if columns.is_empty() {
        return Ok((columns, Vec::new()));
    }
    let object = columns
        .iter()
        .map(|column| format!("'{column}', `{column}`"))
        .collect::<Vec<_>>()
        .join(", ");
    let sql = format!(
        "SELECT CAST(JSON_OBJECT({object}) AS CHAR) AS row_json, id FROM `{table}` \
         WHERE ({filter}) AND id > ? ORDER BY id LIMIT ?"
    );
    let mut out = Vec::new();
    let mut after = 0i64;
    loop {
        let page = sqlx::query(&sql).bind(id).bind(after).bind(PAGE_ROWS).fetch_all(db).await?;
        let Some(last) = page.last() else { break };
        after = last.try_get("id")?;
        for row in &page {
            let text: String = row.try_get("row_json")?;
            out.push(serde_json::from_str(&text)?);
        }
        if (page.len() as i64) < PAGE_ROWS {
            break;
        }
    }
    Ok((columns, out))
}

async fn write_table(
    archive: &mut Archive,
    db: &MySqlPool,
    path: &str,
    table: &str,
    filter: &str,
    id: i64,
    description: &str,
) -> Result<Vec<Value>> {
    let (columns, rows) = table_rows(db, table, filter, id).await?;
    if columns.is_empty() {
        return Ok(rows);
    }
    let mut file = FileWriter::start(archive, path)?;
    file.write(&csv_line(&columns))?;
    for row in &rows {
        let cells: Vec<String> = columns.iter().map(|column| cell(&row[column])).collect();
        file.row(&cells)?;
    }
    file.finish(description, json!({ "columns": columns }))?;
    Ok(rows)
}

fn slug(text: &str) -> String {
    text.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '_' })
        .collect()
}

pub struct ExportOptions {
    pub include_raw_packets: bool,
    pub backend_version: String,
    pub exported_at_ms: i64,
}

/// Build the dataset zip for one experiment.
pub async fn export_experiment(db: &MySqlPool, experiment_id: i64, options: &ExportOptions) -> Result<Vec<u8>> {
    let mut archive = Archive { zip: ZipWriter::new(Cursor::new(Vec::new())), files: Vec::new() };
    let in_sessions = "session_id IN (SELECT id FROM sessions WHERE experiment_id = ?)";

    let (_, experiments) = table_rows(db, "experiments", "id = ?", experiment_id).await?;
    let experiment = experiments.into_iter().next().ok_or_else(|| anyhow!("experiment not found"))?;
    let (_, sessions) = table_rows(db, "sessions", "experiment_id = ?", experiment_id).await?;
    let (_, devices) = table_rows(
        db,
        "devices",
        "id IN (SELECT device_id FROM sessions WHERE experiment_id = ?)",
        experiment_id,
    )
    .await?;
    let (_, measurement_configs) = table_rows(db, "measurement_configs", in_sessions, experiment_id).await?;

    let mut streams = Vec::new();
    for session in &sessions {
        let session_id = session["id"].as_i64().unwrap_or_default();
        let device = devices
            .iter()
            .find(|device| device["id"] == session["device_id"])
            .cloned()
            .unwrap_or(Value::Null);
        let device_slug = slug(device["device_type"].as_str().unwrap_or("device"));
        let stream_names: Vec<String> = sqlx::query_scalar(
            "SELECT DISTINCT stream_name FROM stream_samples WHERE session_id = ? ORDER BY stream_name",
        )
        .bind(session_id)
        .fetch_all(db)
        .await?;
        for stream in stream_names {
            let path = format!("recordings/{session_id}_{device_slug}/{}.csv", slug(&stream));
            streams.push(write_stream(&mut archive, db, session_id, &stream, &path).await?);
        }
        if options.include_raw_packets {
            let path = format!("recordings/{session_id}_{device_slug}/raw_packets.csv");
            write_table(
                &mut archive,
                db,
                &path,
                "raw_packets",
                "session_id = ?",
                session_id,
                "Packets exactly as received from the phone, before parsing (the payload column is the original JSON).",
            )
            .await?;
        }
    }

    write_table(
        &mut archive,
        db,
        "annotations.csv",
        "annotations",
        "experiment_id = ?",
        experiment_id,
        "Labels marking protocol phases and events, with the client's and server's clocks.",
    )
    .await?;
    let mut derived_runs = Vec::new();
    let mut methods = Value::Array(Vec::new());
    if !table_columns(db, "derived_windows").await?.is_empty() {
        write_table(
            &mut archive,
            db,
            "derived/derived_windows.csv",
            "derived_windows",
            in_sessions,
            experiment_id,
            "Derived results, one row per window per method. run_id is empty for rows written live.",
        )
        .await?;
        derived_runs = table_rows(db, "derived_runs", in_sessions, experiment_id).await?.1;
        let summary = sqlx::query(
            "SELECT method_id, method_version, COUNT(*) AS windows, COUNT(hr_bpm) AS with_hr \
             FROM derived_windows WHERE session_id IN (SELECT id FROM sessions WHERE experiment_id = ?) \
             GROUP BY method_id, method_version ORDER BY method_id, method_version",
        )
        .bind(experiment_id)
        .fetch_all(db)
        .await?;
        methods = summary
            .iter()
            .map(|row| {
                json!({
                    "method_id": row.try_get::<String, _>("method_id").unwrap_or_default(),
                    "method_version": row.try_get::<String, _>("method_version").unwrap_or_default(),
                    "windows": row.try_get::<i64, _>("windows").unwrap_or_default(),
                    "windows_with_hr": row.try_get::<i64, _>("with_hr").unwrap_or_default(),
                })
            })
            .collect();
    }
    if !table_columns(db, "live_events").await?.is_empty() {
        write_table(
            &mut archive,
            db,
            "derived/live_alerts.csv",
            "live_events",
            &format!("event_type IN ('alert_raised', 'alert_cleared') AND {in_sessions}"),
            experiment_id,
            "Live data-quality alerts as they were raised and cleared during the recording.",
        )
        .await?;
    }

    let readme = README.replace("{FORMAT_VERSION}", FORMAT_VERSION);
    let mut file = FileWriter::start(&mut archive, "README.md")?;
    file.write(&readme)?;
    file.finish("How to read this export.", json!({}))?;

    let manifest = json!({
        "format": "living-dataset-export",
        "format_version": FORMAT_VERSION,
        "exported_at_ms": options.exported_at_ms,
        "backend_version": options.backend_version,
        "live_recipe_version": crate::live::RECIPE_VERSION,
        "experiment": experiment,
        "sessions": sessions,
        "devices": devices,
        "measurement_configs": measurement_configs,
        "streams": streams,
        "derived_runs": derived_runs,
        "derived_methods": methods,
        "includes_raw_packets": options.include_raw_packets,
        "files": archive.files,
    });
    let mut checksums: String = archive
        .files
        .iter()
        .map(|entry| format!("{}  {}\n", entry["sha256"].as_str().unwrap_or(""), entry["path"].as_str().unwrap_or("")))
        .collect();
    let manifest_text = serde_json::to_string_pretty(&manifest)?;
    checksums.push_str(&format!("{}  manifest.json\n", hex(Sha256::digest(manifest_text.as_bytes()).as_slice())));
    let options_plain = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    archive.zip.start_file("manifest.json", options_plain)?;
    archive.zip.write_all(manifest_text.as_bytes())?;
    archive.zip.start_file("checksums.sha256", options_plain)?;
    archive.zip.write_all(checksums.as_bytes())?;
    Ok(archive.zip.finish()?.into_inner())
}

/// One stream of one recording: fixed timing columns, then one column per
/// channel the stream carried, in name order.
async fn write_stream(archive: &mut Archive, db: &MySqlPool, session_id: i64, stream: &str, path: &str) -> Result<Value> {
    let key_lists: Vec<String> = sqlx::query_scalar(
        "SELECT DISTINCT CAST(JSON_KEYS(channel_values) AS CHAR) FROM stream_samples \
         WHERE session_id = ? AND stream_name = ?",
    )
    .bind(session_id)
    .bind(stream)
    .fetch_all(db)
    .await?;
    let mut channels: Vec<String> = key_lists
        .iter()
        .filter_map(|list| serde_json::from_str::<Vec<String>>(list).ok())
        .flatten()
        .collect();
    channels.sort();
    channels.dedup();
    let units: Option<String> = sqlx::query_scalar(
        "SELECT CAST(channel_units AS CHAR) FROM stream_samples \
         WHERE session_id = ? AND stream_name = ? AND channel_units IS NOT NULL LIMIT 1",
    )
    .bind(session_id)
    .bind(stream)
    .fetch_optional(db)
    .await?
    .flatten();

    let select = SAMPLE_COLUMNS
        .iter()
        .map(|column| format!("CAST(`{column}` AS CHAR) AS `{column}`"))
        .collect::<Vec<_>>()
        .join(", ");
    let sql = format!(
        "SELECT {select}, id AS page_id, CAST(channel_values AS CHAR) AS channel_values FROM stream_samples \
         WHERE session_id = ? AND stream_name = ? AND id > ? ORDER BY id LIMIT ?"
    );

    let mut file = FileWriter::start(archive, path)?;
    let header: Vec<String> = SAMPLE_COLUMNS.iter().map(|c| c.to_string()).chain(channels.iter().cloned()).collect();
    file.write(&csv_line(&header))?;
    let (mut first_ms, mut last_ms) = (None::<i64>, None::<i64>);
    let mut after = 0i64;
    loop {
        let page = sqlx::query(&sql)
            .bind(session_id)
            .bind(stream)
            .bind(after)
            .bind(PAGE_ROWS)
            .fetch_all(db)
            .await?;
        let Some(last) = page.last() else { break };
        after = last.try_get("page_id")?;
        for row in &page {
            let mut cells: Vec<String> = SAMPLE_COLUMNS
                .iter()
                .map(|column| row.try_get::<Option<String>, _>(*column).ok().flatten().unwrap_or_default())
                .collect();
            if let Ok(received) = cells[4].parse::<i64>() {
                first_ms = Some(first_ms.map_or(received, |t| t.min(received)));
                last_ms = Some(last_ms.map_or(received, |t| t.max(received)));
            }
            let values: String = row.try_get("channel_values")?;
            cells.extend(channel_cells(&values, &channels));
            file.row(&cells)?;
        }
        if (page.len() as i64) < PAGE_ROWS {
            break;
        }
    }
    let rows = file.rows;
    let sha256 = file.finish(
        &format!("Stored {stream} samples of recording {session_id}, in storage order."),
        json!({ "columns": header }),
    )?;
    Ok(json!({
        "session_id": session_id,
        "stream_name": stream,
        "path": path,
        "rows": rows,
        "channels": channels,
        "channel_units": units.and_then(|text| serde_json::from_str::<Value>(&text).ok()),
        "first_received_ms": first_ms,
        "last_received_ms": last_ms,
        "sha256": sha256,
    }))
}

const README: &str = r#"# Living dataset export (format {FORMAT_VERSION})

One experiment from the wearable living dataset. `manifest.json` describes every
file (rows, columns, SHA-256); `sha256sum -c checksums.sha256` verifies them.

## Layout

- `recordings/<recording id>_<device>/<stream>.csv`: the stored samples of one
  device recording, one file per stream (ppg, accel, ...), in storage order.
- `recordings/.../raw_packets.csv`: only with `?raw=1`; the packets as received.
- `annotations.csv`: protocol phases and event labels.
- `derived/derived_windows.csv`: derived results, one row per window per method.
- `derived/live_alerts.csv`: live data-quality alerts, raised and cleared.
- `manifest.json`: experiment, recordings, devices, measurement configs,
  derived runs and methods, and the file list with checksums.

## Clocks

All times are Unix milliseconds.

- `received_timestamp_ms`: the time the sample is attributed to (the phone's
  receive time where available).
- `backend_received_timestamp_ms`: when the cloud backend stored it. Live
  analysis and recomputes run on this clock.
- `android_received_timestamp_ms`: when the phone received it from the device.
- `source_timestamp_value` / `_unit`: the device's own time, if it reports one.
  `unix_ms_unverif` (short for unverified) means Unix milliseconds that the
  device states but that have not been checked against another clock.
- `sample_index`: assigned by the phone per sample. It restarts when a device
  reconnects, so it is unique only together with `raw_packet_id`, not alone.

## Derived results

`method_id` and `method_version` name what produced a row: `live_ppg` is the
live algorithm (version = live recipe; `live-eda/1.0.0` is the same recipe
under its earlier name), `papagei_s+<head>` is the PaPaGei-S
model with a regression head (version = head version). `run_id` links a row to
`derived_runs` in the manifest, which records the evidence row count and
SHA-256 the run read; an empty `run_id` means the row was written live.
Derived rows can always be recomputed from the sample files; the samples are
the evidence.
"#;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csv_cells_with_commas_and_quotes_are_quoted() {
        let line = csv_line(&["a".into(), "b,c".into(), "say \"hi\"".into(), String::new()]);
        assert_eq!(line, "a,\"b,c\",\"say \"\"hi\"\"\",\n");
    }

    #[test]
    fn slugs_are_file_safe() {
        assert_eq!(slug("OpenEarable 2"), "openearable_2");
    }
}
