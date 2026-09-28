#![cfg_attr(not(test), windows_subsystem = "windows")]

#[cfg(not(all(windows, target_arch = "x86_64")))]
compile_error!("Celeste+ Mini supports Windows x64.");

mod protocol;
mod storage;
mod ui;

use anyhow::{Context, Result};
use std::sync::{Arc, atomic::Ordering};

fn open_loader(progress: Arc<ui::Progress>) -> Result<()> {
    let agent = protocol::agent();
    let manifest = protocol::manifest(&agent, &progress.cancel)?;
    protocol::cancelled(&progress.cancel)?;
    let temporary = storage::PrivateTemp::create()
        .context("Couldn't prepare the temporary download folder.")?;
    let file = temporary.create_download().with_context(|| {
        format!(
            "Couldn't create the download in {}.",
            temporary.path().display()
        )
    })?;
    progress.phase.store(1, Ordering::Release);
    protocol::download(&agent, &manifest, &file, &progress.cancel, |done, total| {
        progress
            .percent
            .store((done * 100 / total) as u32, Ordering::Release);
    })
    .context("Couldn't download Celeste+.")?;
    file.sync_all()?;
    drop(file);
    progress.phase.store(2, Ordering::Release);
    let guard = temporary
        .locked_verified_executable(&manifest.sha256, manifest.size)
        .context("Couldn't verify the downloaded loader.")?;
    protocol::refresh_if_needed(&agent, &manifest, &progress.cancel)?;
    protocol::cancelled(&progress.cancel)?;
    temporary
        .execute_and_cleanup(guard, || {
            progress.launched.store(true, Ordering::Release);
        })
        .context("Couldn't open Celeste+.")
}

fn main() {
    if let Err(error) = ui::run(open_loader) {
        ui::error(&format!("Couldn't start Celeste+.\n\n{error:#}"));
    }
}
