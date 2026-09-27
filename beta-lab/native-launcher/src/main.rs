#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

#[path = "../../native-shared/src/artifact_envelope.rs"]
mod artifact_envelope;
mod atomic_store;
mod attach;
mod auth;
mod bootstrap_cache;
#[cfg(windows)]
mod credential_protection;
#[cfg(any(target_os = "macos", target_os = "linux"))]
#[path = "../../native-shared/src/portable_credential_protection.rs"]
mod credential_protection;
#[path = "../../native-shared/src/debug_guard.rs"]
mod debug_guard;
mod delivery;
#[path = "../../../shared-auth/dpop.rs"]
mod dpop;
#[path = "../../native-shared/src/environment_guard.rs"]
mod environment_guard;
#[path = "../../native-shared/src/hardware_v2.rs"]
mod hardware_v2;
#[path = "../../native-shared/src/native_manifest.rs"]
mod native_manifest;
mod platform;
mod release;
#[path = "../../native-shared/src/transport_policy.rs"]
mod transport_policy;
#[path = "../../native-shared/src/user_profile.rs"]
mod user_profile;
#[cfg(windows)]
mod windows_profile;

#[cfg(not(target_os = "macos"))]
pub(crate) const SETUP_BUILD: &str = "20260920.3";
#[cfg(target_os = "macos")]
pub(crate) const SETUP_BUILD: &str = "20260920.4-macos";

use anyhow::{Result, anyhow};
use attach::{AttachContext, Report};
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Frame, Id, Layout, Margin, RichText, Stroke, Vec2,
    Visuals,
};
use platform::{GameProfile, MinecraftProcess};
use serde_json::json;
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::{Duration, Instant};

const BG: Color32 = Color32::from_rgb(18, 18, 22);
const CARD: Color32 = Color32::from_rgb(31, 31, 36);
const INPUT_BG: Color32 = Color32::from_rgb(24, 24, 28);
const ROW: Color32 = Color32::from_rgb(38, 38, 44);
const ROW_HOVER: Color32 = Color32::from_rgb(45, 45, 52);
const TEXT: Color32 = Color32::from_rgb(230, 230, 236);
const MUTED: Color32 = Color32::from_rgb(154, 158, 172);
const ACCENT: Color32 = Color32::from_rgb(255, 79, 163);
const SUCCESS: Color32 = Color32::from_rgb(110, 200, 140);
const FAILURE: Color32 = Color32::from_rgb(255, 110, 120);

#[derive(Clone, Copy)]
struct UiMetrics {
    factor: f32,
    card_width: f32,
    body_height: f32,
}

impl UiMetrics {
    fn from_screen(rect: egui::Rect) -> Self {
        let width = rect.width().max(1.0);
        let height = rect.height().max(1.0);
        let factor = (width / 440.0).min(height / 380.0).clamp(0.9, 1.5);
        Self {
            factor,
            card_width: (360.0 * factor).min((width - 72.0 * factor).max(1.0)),
            // Header, card padding and window margins stay outside the scroll body.
            body_height: (height - 132.0 * factor).max(130.0),
        }
    }

    fn margin(self, value: f32) -> i8 {
        (value * self.factor).round().clamp(1.0, 127.0) as i8
    }
}

enum Event {
    Activated,
    ActivationNeeded,
    Targets(Vec<GameProfile>, Vec<MinecraftProcess>, String, String),
    ActivationFailed(String),
    ActivationRequired(String),
    Progress(String),
    Context(AttachContext),
    Report(Report),
    Failure(String),
}

struct App {
    setup_ready: bool,
    setup_busy: bool,
    checking: bool,
    details_open: bool,
    license: String,
    game: String,
    profiles: Vec<GameProfile>,
    processes: Vec<MinecraftProcess>,
    selected: Option<MinecraftProcess>,
    jdk: String,
    busy: bool,
    phase: String,
    status: String,
    error: String,
    attached: Option<AttachContext>,
    rx: Receiver<Event>,
    tx: Sender<Event>,
    polled: Instant,
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        apply_theme(&cc.egui_ctx);
        let (tx, rx) = mpsc::channel();
        let mut app = Self {
            setup_ready: false,
            setup_busy: false,
            checking: false,
            details_open: false,
            license: String::new(),
            game: String::new(),
            profiles: Vec::new(),
            processes: Vec::new(),
            selected: None,
            jdk: String::new(),
            busy: false,
            phase: "idle".into(),
            status: "Choose your profile and running Minecraft process, then Load.".into(),
            error: String::new(),
            attached: None,
            rx,
            tx,
            polled: Instant::now(),
        };
        app.activate(true);
        app
    }

    fn activate(&mut self, saved: bool) {
        if !self.can_change_license() {
            return;
        }
        let license = if saved {
            String::new()
        } else {
            self.license.trim().to_owned()
        };
        if !saved && !(20..=96).contains(&license.len()) {
            self.error = "Paste your complete Celeste+ or Both license key.".into();
            return;
        }
        self.setup_ready = false;
        self.setup_busy = true;
        self.error.clear();
        self.status = if saved {
            "Checking saved activationâ€¦"
        } else {
            "Activating this PCâ€¦"
        }
        .into();
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let event = match auth::activate(&license) {
                Ok(true) => {
                    let _ = tx.send(Event::Activated);
                    let (profiles, error) = match platform::modrinth_profiles() {
                        Ok(profiles) => (profiles, String::new()),
                        Err(error) => (
                            Vec::new(),
                            format!(
                                "Could not find profiles: {error:#}. Set your game folder in Details."
                            ),
                        ),
                    };
                    let processes = platform::minecraft_processes();
                    let jdk = platform::find_jdk()
                        .map(|path| path.display().to_string())
                        .unwrap_or_default();
                    Event::Targets(profiles, processes, jdk, error)
                }
                Ok(false) => Event::ActivationNeeded,
                Err(error) => {
                    let mut message = format!("{error:#}");
                    if !license.is_empty() {
                        message = message.replace(&license, "[redacted license]");
                    }
                    Event::ActivationFailed(message)
                }
            };
            let _ = tx.send(event);
        });
    }

    fn start(&mut self) {
        if !self.setup_ready || self.setup_busy {
            return;
        }
        if !can_start(
            self.busy || self.checking,
            self.attached.is_some(),
            &self.phase,
            self.selected.is_some(),
        ) {
            self.error = "Wait for confirmed cleanup before loading again. Check Details for the current host state.".into();
            return;
        }
        let target = match self.selected.clone() {
            Some(target) => target,
            None => {
                self.error = "Explicitly select one running Minecraft window and PID.".into();
                return;
            }
        };
        let game = match platform::canonical_directory(&self.game) {
            Ok(game) => game,
            Err(error) => {
                self.error = format!("{error:#}");
                return;
            }
        };
        let java = match platform::validate_jdk(&PathBuf::from(self.jdk.trim())) {
            Ok(java) => java,
            Err(error) => {
                self.error = format!("{error:#}");
                return;
            }
        };
        let license = std::mem::take(&mut self.license);
        let retry = self.attached.clone();
        let tx = self.tx.clone();
        self.busy = true;
        self.error.clear();
        self.phase = "authenticating".into();
        self.status = "Authenticating the existing installation with both signed key proofs and current hardware.".into();
        std::thread::spawn(move || {
            let result = (|| -> Result<()> {
                platform::recheck_process(&target)?;
                if let Some(previous) = &retry {
                    if previous.target.pid != target.pid
                        || previous.target.started != target.started
                        || previous.java != java
                        || previous.game != game
                    {
                        return Err(anyhow!(
                            "Retry must keep the original selected PID, creation time, game directory and JDK"
                        ));
                    }
                    // Reconfirm inactivity now, not just from an earlier UI status.
                    let observed = attach::command(previous, json!({"action":"status"}))?;
                    if !is_reusable_phase(&observed.phase) {
                        let _ = tx.send(Event::Report(observed));
                        return Ok(());
                    }
                }
                let mut session = match auth::authenticate(&game, &license) {
                    Ok(session) => session,
                    Err(error) if auth::needs_activation(&error) => {
                        let _ = tx.send(Event::ActivationRequired(format!("{error:#}")));
                        return Ok(());
                    }
                    Err(error) => return Err(error),
                };
                session.validate_for_launch(&game)?;
                let _ = tx.send(Event::Progress(
                    "Celeste+ activated. Preparing the signed release.".into(),
                ));
                let support = platform::support_directory(target.pid, target.started)?;
                let verified = match delivery::download_bootstrap(&session, &support) {
                    Ok(value) => value,
                    Err(error) if session.recover_authorization(&error)? => {
                        session.validate_for_launch(&game)?;
                        match delivery::download_bootstrap(&session, &support) {
                            Ok(value) => value,
                            Err(error) => {
                                session.reject_if_authoritative(&game, &error)?;
                                return Err(error);
                            }
                        }
                    }
                    Err(error) => {
                        session.reject_if_authoritative(&game, &error)?;
                        return Err(error);
                    }
                };
                let context = if let Some(previous) = retry {
                    attach::verify_retry_artifact(
                        &previous,
                        &verified.sha256,
                        &verified.release,
                        &verified.catalog_sha256,
                    )?;
                    previous
                } else {
                    AttachContext {
                        target,
                        java,
                        sha256: verified.sha256,
                        agent: verified.agent,
                        support,
                        game,
                        release: verified.release,
                        catalog_sha256: verified.catalog_sha256,
                    }
                };
                // Keep exactly this agent for all controls after inspection loads it.
                let _ = tx.send(Event::Context(context.clone()));
                let _ = tx.send(Event::Progress("Inspecting the selected PID and checking its actual Minecraft directory. No feature hooks yet.".into()));
                let inspection = attach::command(&context, json!({"action":"inspect"}))?;
                attach::verify_inspection(&context, &inspection)?;
                session.prepare_runtime_credential(&context.game)?;
                session.validate_for_launch(&context.game)?;
                let _ = tx.send(Event::Progress("Target identity matches. Starting the signed beta chain; waiting for actual runtime state.".into()));
                let report = attach::command(
                    &context,
                    attach::start_request(&context, &session.grant, &session.device, session.uid),
                )?;
                let _ = tx.send(Event::Report(report));
                Ok(())
            })();
            if let Err(error) = result {
                let detail = format!("{error:#}");
                let detail = if license.is_empty() {
                    detail
                } else {
                    detail.replace(&license, "[redacted license]")
                };
                let _ = tx.send(if auth::needs_activation(&error) {
                    Event::ActivationRequired(detail)
                } else {
                    Event::Failure(detail)
                });
            }
        });
    }

    fn control(&mut self, action: &'static str) {
        let Some(context) = self.attached.clone() else {
            return;
        };
        if self.busy || self.checking {
            return;
        }
        self.checking = action == "status";
        self.busy = !self.checking;
        self.polled = Instant::now();
        if action == "stop" {
            self.phase = "stopping".into();
            self.status = "Requesting stop; waiting for cleanup confirmation.".into();
        }
        let tx = self.tx.clone();
        std::thread::spawn(
            move || match attach::command(&context, json!({"action":action})) {
                Ok(report) => {
                    let _ = tx.send(Event::Report(report));
                }
                Err(error) => {
                    let _ = tx.send(Event::Failure(format!("{error:#}")));
                }
            },
        );
    }

    fn events(&mut self) {
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::ActivationNeeded => {
                    self.setup_ready = false;
                    self.setup_busy = false;
                    self.error.clear();
                    self.status = "Enter your license to get started.".into();
                }
                Event::Activated => {
                    self.setup_ready = true;
                    self.setup_busy = false;
                    self.license.clear();
                    self.error.clear();
                    self.status =
                        "Start Minecraft, select its profile and window, then Load.".into();
                    if self.attached.is_none() {
                        self.phase = "idle".into();
                    }
                }
                Event::Targets(profiles, processes, jdk, error) => {
                    if self.attached.is_some() {
                        continue;
                    }
                    if self.game.trim().is_empty() {
                        self.game = initial_game(&profiles);
                    }
                    self.profiles = profiles;
                    self.replace_processes(processes);
                    if self.jdk.trim().is_empty() {
                        self.jdk = jdk;
                    }
                    if !error.is_empty() {
                        self.error = error;
                        self.details_open = true;
                    }
                }
                Event::ActivationRequired(message) => {
                    // Start confirms an existing host is inactive before auth;
                    // no start request has been sent on this failure path.
                    if self.attached.is_some() {
                        self.phase = "inactive".into();
                    }
                    self.setup_ready = false;
                    self.setup_busy = false;
                    self.busy = false;
                    self.checking = false;
                    self.error = message;
                    self.status = "Enter your current license to continue.".into();
                }
                Event::ActivationFailed(message) => {
                    self.setup_ready = false;
                    self.setup_busy = false;
                    self.busy = false;
                    self.checking = false;
                    self.error = message;
                    self.status = "Activate once. Your activation is saved for next time.".into();
                }
                Event::Progress(message) => self.status = message,
                Event::Context(context) => self.attached = Some(context),
                Event::Failure(message) => {
                    self.busy = false;
                    self.checking = false;
                    self.phase = "failed".into();
                    self.error = message;
                    self.details_open = true;
                    self.status = if self.attached.is_some() {
                        "The request did not complete. Check the error and host status in Details."
                            .into()
                    } else {
                        "Authentication or signed delivery did not complete. No attachment occurred.".into()
                    };
                }
                Event::Report(report) => {
                    self.busy = false;
                    self.checking = false;
                    self.phase = report.phase.clone();
                    self.error = report.error;
                    if !self.error.is_empty() {
                        self.details_open = true;
                    }
                    self.status = match report.phase.as_str() {
                        "active" => "Active: the beta reports authenticated initialization and resource readiness.".into(),
                        "starting" => "Starting: authentication/resources are still pending. Not active yet.".into(),
                        "stopping" => "Stopping: waiting for callbacks, resources and hook cleanup.".into(),
                        "stopped" => "Cleanup confirmed. Load again when ready.".into(),
                        "inactive" => "Ready to load into the same selected game.".into(),
                        "failed" => "Beta reported failure. Stop it or restart Minecraft before trying another release.".into(),
                        "restart required" => "Cleanup could not be confirmed. Restart Minecraft before another beta lifetime.".into(),
                        other => format!("Runtime phase: {other}"),
                    };
                    self.polled = Instant::now();
                }
            }
        }
    }
}

impl eframe::App for App {
    fn clear_color(&self, _visuals: &Visuals) -> [f32; 4] {
        BG.to_normalized_gamma_f32()
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        apply_theme(ctx);
        self.events();
        if self.attached.is_some()
            && !self.busy
            && !self.checking
            && poll_interval(&self.phase).is_some_and(|interval| self.polled.elapsed() >= interval)
        {
            self.control("status");
        }
        if ctx.input(|input| input.viewport().close_requested()) {
            if self.busy || self.checking || self.setup_busy {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.status =
                    "A request is in progress. Wait for its result before closing this launcher."
                        .into();
            } else if self.attached.is_some()
                && !["stopped", "inactive", "restart required", "failed"]
                    .contains(&self.phase.as_str())
            {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.control("stop");
            }
        }
        self.show_ui(ctx);
        ctx.request_repaint_after(Duration::from_millis(120));
    }
}

impl App {
    fn can_change_license(&self) -> bool {
        !self.setup_busy
            && !self.busy
            && !self.checking
            && (self.attached.is_none() || is_reusable_phase(&self.phase))
    }

    fn can_change_target(&self) -> bool {
        self.setup_ready
            && !self.setup_busy
            && !self.busy
            && !self.checking
            && self.attached.is_none()
    }

    fn select_profile(&mut self, index: usize) {
        if !self.can_change_target() {
            return;
        }
        if let Some(profile) = self.profiles.get(index) {
            self.game = profile.game.display().to_string();
        }
    }

    fn select_process(&mut self, index: usize) {
        if !self.can_change_target() {
            return;
        }
        if let Some(process) = self.processes.get(index) {
            self.selected = Some(process.clone());
        }
    }

    fn refresh_processes(&mut self) {
        if !self.can_change_target() {
            return;
        }
        self.replace_processes(platform::minecraft_processes());
    }

    fn replace_processes(&mut self, processes: Vec<MinecraftProcess>) {
        if let Some(selected) = &self.selected {
            self.selected = processes
                .iter()
                .find(|process| process.pid == selected.pid && process.started == selected.started)
                .cloned();
        }
        self.processes = processes;
    }

    fn show_ui(&mut self, ctx: &egui::Context) {
        let metrics = UiMetrics::from_screen(ctx.screen_rect());
        egui::CentralPanel::default()
            .frame(Frame::new().fill(BG).inner_margin(Margin::ZERO))
            .show(ctx, |_ui| {
                egui::Area::new(Id::new("celeste_owner_card"))
                    .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
                    .show(ctx, |ui| {
                        ui.spacing_mut().item_spacing = Vec2::new(8.0, 8.0) * metrics.factor;
                        ui.spacing_mut().button_padding = Vec2::new(10.0, 6.0) * metrics.factor;
                        Frame::new()
                            .fill(CARD)
                            .stroke(Stroke::NONE)
                            .corner_radius(16.0 * metrics.factor)
                            .inner_margin(Margin::symmetric(
                                metrics.margin(18.0),
                                metrics.margin(20.0),
                            ))
                            .show(ui, |ui| {
                                ui.set_width(metrics.card_width);
                                ui.set_max_width(metrics.card_width);
                                ui.with_layout(Layout::top_down(Align::Center), |ui| {
                                    ui.label(
                                        RichText::new("Celeste+")
                                            .color(ACCENT)
                                            .font(FontId::proportional(27.0 * metrics.factor))
                                            .strong(),
                                    );
                                    ui.label(
                                        RichText::new(if self.setup_ready {
                                            "LIVE LOAD"
                                        } else {
                                            "ACTIVATE YOUR LICENSE"
                                        })
                                        .color(MUTED)
                                        .size(10.0 * metrics.factor),
                                    );
                                });
                                ui.add_space(4.0 * metrics.factor);
                                egui::ScrollArea::vertical()
                                    .id_salt("owner_details_body")
                                    .max_height(
                                        (metrics.body_height - 78.0 * metrics.factor).max(100.0),
                                    )
                                    .auto_shrink([false, true])
                                    .show(ui, |ui| {
                                        if self.setup_ready {
                                            self.show_main(ui, &metrics);
                                        } else {
                                            self.show_activation(ui, &metrics);
                                        }
                                    });
                                if self.setup_ready {
                                    self.show_main_footer(ui, &metrics);
                                }
                            });
                    });
            });
    }

    fn show_activation(&mut self, ui: &mut egui::Ui, metrics: &UiMetrics) {
        ui.label(
            RichText::new(
                "Enter your license to get started. You only need to do this once on this computer.",
            )
            .size(12.0 * metrics.factor)
            .color(MUTED),
        );
        ui.add_space(6.0 * metrics.factor);
        ui.label(
            RichText::new("License key")
                .color(TEXT)
                .size(12.0 * metrics.factor),
        );
        let input = ui.add_enabled(
            !self.setup_busy,
            egui::TextEdit::singleline(&mut self.license)
                .password(true)
                .hint_text("Celeste+ or Both license")
                .desired_width(ui.available_width()),
        );
        let submit = input.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
        let valid = (20..=96).contains(&self.license.trim().len());
        let activate = ui
            .add_enabled(
                !self.setup_busy && valid,
                egui::Button::new(if self.setup_busy {
                    "Checkingâ€¦"
                } else {
                    "Activate"
                })
                .fill(ACCENT)
                .stroke(Stroke::NONE)
                .corner_radius(9.0)
                .min_size(Vec2::new(ui.available_width(), 38.0 * metrics.factor)),
            )
            .clicked();
        if (activate || submit) && !self.setup_busy && valid {
            self.activate(false);
        }
        if self.setup_busy {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label(RichText::new(&self.status).color(MUTED).size(11.5));
            });
            #[cfg(target_os = "macos")]
            ui.add(egui::Label::new(RichText::new(
                "If Keychain asks, approve access to the Celeste item. Choose Always Allow if you want Minecraft to read it without asking again.",
            ).color(MUTED).size(11.5)).wrap());
        } else if !self.error.is_empty() {
            ui.add(egui::Label::new(RichText::new(&self.error).color(FAILURE).size(11.5)).wrap());
            ui.horizontal(|ui| {
                if ui.small_button("Copy error").clicked() {
                    ui.ctx().copy_text(format!(
                        "Celeste+ setup build {SETUP_BUILD}\n{}",
                        self.error
                    ));
                }
                ui.label(RichText::new(SETUP_BUILD).size(10.0).color(MUTED));
            });
            if ui.button("Retry saved activation").clicked() {
                self.activate(true);
            }
        }
    }

    fn show_main(&mut self, ui: &mut egui::Ui, metrics: &UiMetrics) {
        let editable = self.can_change_target();
        ui.label(
            RichText::new("Profile")
                .color(MUTED)
                .size(11.0 * metrics.factor),
        );
        let profile = if !self.game.trim().is_empty()
            && !self
                .profiles
                .iter()
                .any(|profile| profile.game.display().to_string() == self.game)
        {
            "Custom game folder".into()
        } else {
            profile_selection_label(&self.profiles, &self.game)
        };
        let mut profile_index = None;
        ui.add_enabled_ui(editable, |ui| {
            egui::ComboBox::from_id_salt("owner_profile")
                .width(ui.available_width())
                .selected_text(profile)
                .truncate()
                .height(180.0 * metrics.factor)
                .show_ui(ui, |ui| {
                    if self.profiles.is_empty() {
                        ui.label("Set the game folder in Details.");
                    }
                    for (index, profile) in self.profiles.iter().enumerate() {
                        if ui
                            .selectable_label(
                                self.game == profile.game.display().to_string(),
                                &profile.name,
                            )
                            .on_hover_text(profile.game.display().to_string())
                            .clicked()
                        {
                            profile_index = Some(index);
                        }
                    }
                });
        });
        if let Some(index) = profile_index {
            self.select_profile(index);
        }
        ui.label(
            RichText::new("Running Minecraft")
                .color(MUTED)
                .size(11.0 * metrics.factor),
        );
        let selected = self
            .selected
            .as_ref()
            .map(|process| format!("{} Â· {}", process.title, process.pid))
            .unwrap_or_else(|| {
                if cfg!(windows) {
                    "Choose your game window"
                } else {
                    "Choose Minecraft's Java process"
                }
                .into()
            });
        let mut process_index = None;
        ui.horizontal(|ui| {
            let refresh_width = 62.0 * metrics.factor;
            let width =
                (ui.available_width() - refresh_width - ui.spacing().item_spacing.x).max(80.0);
            ui.add_enabled_ui(editable, |ui| {
                // ComboBox::width is a minimum; bound its actual layout so a long
                // window title cannot consume the adjacent Refresh button's space.
                ui.set_width(width);
                egui::ComboBox::from_id_salt("owner_process")
                    .width(width)
                    .selected_text(selected)
                    .truncate()
                    .height(180.0 * metrics.factor)
                    .show_ui(ui, |ui| {
                        if self.processes.is_empty() {
                            ui.label("Start Minecraft, then Refresh.");
                        }
                        for (index, process) in self.processes.iter().enumerate() {
                            let current = self.selected.as_ref().is_some_and(|selected| {
                                selected.pid == process.pid && selected.started == process.started
                            });
                            if ui
                                .selectable_label(
                                    current,
                                    format!("{} Â· PID {}", process.title, process.pid),
                                )
                                .clicked()
                            {
                                process_index = Some(index);
                            }
                        }
                    });
            });
            if ui
                .add_enabled(
                    editable,
                    secondary_button("Refresh", refresh_width, metrics),
                )
                .clicked()
            {
                self.refresh_processes();
            }
        });
        if let Some(index) = process_index {
            self.select_process(index);
        }
        if ui
            .add(
                egui::Button::new(
                    RichText::new(if self.details_open {
                        "Hide details"
                    } else {
                        "Details"
                    })
                    .color(MUTED)
                    .size(11.5 * metrics.factor),
                )
                .frame(false),
            )
            .clicked()
        {
            self.details_open = !self.details_open;
        }
        if self.details_open {
            self.show_details(ui, metrics);
        }
    }

    fn show_details(&mut self, ui: &mut egui::Ui, metrics: &UiMetrics) {
        if !self.error.is_empty() {
            ui.add(
                egui::Label::new(
                    RichText::new(&self.error)
                        .color(FAILURE)
                        .size(11.5 * metrics.factor),
                )
                .wrap(),
            );
        }
        ui.add(
            egui::Label::new(
                RichText::new(&self.status)
                    .color(MUTED)
                    .size(11.0 * metrics.factor),
            )
            .wrap(),
        );
        if ui
            .add_enabled(
                self.can_change_license(),
                egui::Button::new("Change license"),
            )
            .clicked()
        {
            self.setup_ready = false;
            self.license.clear();
            self.error.clear();
        }
        ui.add_enabled_ui(self.can_change_target(), |ui| {
            ui.label(
                RichText::new("Game folder")
                    .color(MUTED)
                    .size(11.0 * metrics.factor),
            );
            ui.add(egui::TextEdit::singleline(&mut self.game).desired_width(ui.available_width()));
            ui.label(
                RichText::new("Java 21 JDK")
                    .color(MUTED)
                    .size(11.0 * metrics.factor),
            );
            ui.add(egui::TextEdit::singleline(&mut self.jdk).desired_width(ui.available_width()));
        });
        if let Some(context) = &self.attached {
            ui.label(
                RichText::new(format!("PID {} Â· {}", context.target.pid, context.release))
                    .color(MUTED)
                    .size(10.5 * metrics.factor),
            );
            if ui
                .add_enabled(
                    !self.busy && !self.checking,
                    secondary_button("Check status", ui.available_width(), metrics),
                )
                .clicked()
            {
                self.control("status");
            }
        }
    }

    fn show_main_footer(&mut self, ui: &mut egui::Ui, metrics: &UiMetrics) {
        ui.add_space(4.0 * metrics.factor);
        let action = main_action(self.attached.is_some(), &self.phase, self.busy);
        let enabled = !self.checking
            && match action {
                MainAction::Load => {
                    !self.game.trim().is_empty()
                        && can_start(
                            self.busy,
                            self.attached.is_some(),
                            &self.phase,
                            self.selected.is_some(),
                        )
                }
                MainAction::Stop => !self.busy,
                MainAction::Waiting | MainAction::Restart => false,
            };
        let label = match action {
            MainAction::Load => "Load",
            MainAction::Stop => "Stop",
            MainAction::Restart => "Restart Minecraft",
            MainAction::Waiting if self.phase == "stopping" => "Stoppingâ€¦",
            MainAction::Waiting => "Loadingâ€¦",
        };
        if ui
            .with_layout(Layout::top_down(Align::Center), |ui| {
                ui.add_enabled(
                    enabled,
                    egui::Button::new(
                        RichText::new(label)
                            .color(Color32::WHITE)
                            .size(15.0 * metrics.factor)
                            .strong(),
                    )
                    .fill(ACCENT)
                    .stroke(Stroke::NONE)
                    .corner_radius(8.0 * metrics.factor)
                    .min_size(Vec2::new(ui.available_width(), 40.0 * metrics.factor)),
                )
            })
            .inner
            .clicked()
        {
            match action {
                MainAction::Load => self.start(),
                MainAction::Stop => self.control("stop"),
                _ => {}
            }
        }
        let message = if !self.error.is_empty() {
            self.error
                .lines()
                .next()
                .unwrap_or("Check Details for the error.")
        } else {
            match self.phase.as_str() {
                "active" => "Loaded",
                "starting" | "authenticating" => "Loading Celesteâ€¦",
                "stopping" => "Waiting for cleanupâ€¦",
                "stopped" | "inactive" => "Ready to load again",
                "failed" => "Check Details to continue",
                "restart required" => "Restart Minecraft before loading again",
                _ if self.selected.is_none() || self.game.trim().is_empty() => {
                    "Choose your profile and game"
                }
                _ => "Ready to load",
            }
        };
        ui.with_layout(Layout::top_down(Align::Center), |ui| {
            ui.add(
                egui::Label::new(RichText::new(message).size(11.0 * metrics.factor).color(
                    if !self.error.is_empty() {
                        FAILURE
                    } else if self.phase == "active" {
                        SUCCESS
                    } else {
                        MUTED
                    },
                ))
                .truncate(),
            )
            .on_hover_text(message);
        });
    }
}

fn secondary_button(text: &str, width: f32, metrics: &UiMetrics) -> egui::Button<'static> {
    egui::Button::new(
        RichText::new(text.to_owned())
            .color(TEXT)
            .size(13.0 * metrics.factor),
    )
    .fill(ROW)
    .stroke(Stroke::NONE)
    .corner_radius(8.0 * metrics.factor)
    .min_size(Vec2::new(width, 30.0 * metrics.factor))
}

fn apply_theme(ctx: &egui::Context) {
    ctx.set_theme(egui::ThemePreference::Dark);
    let mut visuals = Visuals::dark();
    visuals.panel_fill = BG;
    visuals.window_fill = CARD;
    visuals.faint_bg_color = CARD;
    visuals.extreme_bg_color = INPUT_BG;
    visuals.text_edit_bg_color = Some(INPUT_BG);
    visuals.override_text_color = Some(TEXT);
    visuals.weak_text_color = Some(MUTED);
    visuals.hyperlink_color = ACCENT;
    visuals.selection.bg_fill = Color32::from_rgba_unmultiplied(255, 79, 163, 90);
    visuals.selection.stroke = Stroke::new(1.0_f32, ACCENT);
    visuals.text_cursor.stroke = Stroke::new(1.0_f32, ACCENT);
    visuals.window_stroke = Stroke::NONE;
    visuals.window_shadow = egui::Shadow::NONE;
    visuals.popup_shadow = egui::Shadow::NONE;
    visuals.disabled_alpha = 0.72;
    for (widget, fill, foreground) in [
        (&mut visuals.widgets.noninteractive, CARD, MUTED),
        (&mut visuals.widgets.inactive, ROW, TEXT),
        (&mut visuals.widgets.hovered, ROW_HOVER, TEXT),
        (
            &mut visuals.widgets.active,
            Color32::from_rgb(42, 42, 50),
            TEXT,
        ),
        (&mut visuals.widgets.open, ROW, TEXT),
    ] {
        widget.bg_fill = fill;
        widget.weak_bg_fill = fill;
        widget.bg_stroke = Stroke::NONE;
        widget.fg_stroke = Stroke::new(1.0_f32, foreground);
        widget.corner_radius = egui::CornerRadius::same(8);
        widget.expansion = 0.0;
    }
    visuals.widgets.active.bg_stroke = Stroke::new(1.0_f32, ACCENT);
    ctx.all_styles_mut(|style| {
        style.visuals = visuals.clone();
        style.spacing.button_padding = Vec2::new(14.0, 8.0);
        style.spacing.item_spacing = Vec2::new(10.0, 10.0);
    });
}

// With several installations, neither database order nor a Minecraft window
// title identifies the intended profile. Require the user's explicit choice.
fn initial_game(profiles: &[GameProfile]) -> String {
    match profiles {
        [profile] => profile.game.display().to_string(),
        _ => String::new(),
    }
}

fn profile_selection_label(profiles: &[GameProfile], game: &str) -> String {
    profiles
        .iter()
        .find(|profile| profile.game.display().to_string() == game)
        .map(|profile| profile.name.clone())
        .unwrap_or_else(|| "Choose the profile running in Minecraft".into())
}

fn can_start(busy: bool, has_context: bool, phase: &str, selected: bool) -> bool {
    !busy && selected && (!has_context || is_reusable_phase(phase))
}

fn is_reusable_phase(phase: &str) -> bool {
    matches!(phase, "inactive" | "stopped")
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MainAction {
    Load,
    Stop,
    Waiting,
    Restart,
}

fn main_action(has_context: bool, phase: &str, busy: bool) -> MainAction {
    if phase == "restart required" && has_context {
        MainAction::Restart
    } else if busy || phase == "stopping" {
        MainAction::Waiting
    } else if !has_context || is_reusable_phase(phase) {
        MainAction::Load
    } else {
        MainAction::Stop
    }
}

fn poll_interval(phase: &str) -> Option<Duration> {
    match phase {
        "starting" | "stopping" => Some(Duration::from_secs(1)),
        "active" => Some(Duration::from_secs(4)),
        _ => None,
    }
}
#[cfg(test)]
mod ui_state_tests {
    use super::*;
    #[test]
    fn multiple_profiles_require_an_explicit_choice_in_any_database_order() {
        let first = GameProfile {
            name: "First installation".into(),
            game: PathBuf::from(r"C:\profiles\first"),
        };
        let second = GameProfile {
            name: "QA installation".into(),
            game: PathBuf::from(r"C:\profiles\qa"),
        };
        assert!(initial_game(&[]).is_empty());
        assert_eq!(
            initial_game(std::slice::from_ref(&first)),
            first.game.display().to_string()
        );
        assert!(initial_game(&[first.clone(), second.clone()]).is_empty());
        assert!(initial_game(&[second, first]).is_empty());
    }

    #[test]
    fn profile_label_shows_the_explicit_selection() {
        let profiles = [
            GameProfile {
                name: "First installation".into(),
                game: PathBuf::from(r"C:\profiles\first"),
            },
            GameProfile {
                name: "QA installation".into(),
                game: PathBuf::from(r"C:\profiles\qa"),
            },
        ];
        assert_eq!(
            profile_selection_label(&profiles, &initial_game(&profiles)),
            "Choose the profile running in Minecraft"
        );
        assert_eq!(
            profile_selection_label(&profiles, &profiles[1].game.display().to_string()),
            "QA installation"
        );
        assert_eq!(
            profile_selection_label(&profiles, &profiles[0].game.display().to_string()),
            "First installation"
        );
    }

    #[test]
    fn selectors_only_select_and_never_start_authentication() {
        let mut app = fixture_app();
        app.select_profile(1);
        assert_eq!(app.game, app.profiles[1].game.display().to_string());
        assert!(app.selected.is_none());
        app.select_process(1);
        assert_eq!(app.selected.as_ref().unwrap().pid, 200);
        assert_eq!(app.selected.as_ref().unwrap().started, 2);
        assert!(!app.busy);
        assert!(app.attached.is_none());
        assert_eq!(app.phase, "idle");
        assert!(app.rx.try_recv().is_err());
    }

    #[test]
    fn busy_and_pinned_targets_cannot_be_changed_through_selectors() {
        let mut app = fixture_app();
        app.select_profile(0);
        app.select_process(0);
        let original_game = app.game.clone();
        app.busy = true;
        app.select_profile(1);
        app.select_process(1);
        assert_eq!(app.game, original_game);
        assert_eq!(app.selected.as_ref().unwrap().pid, 100);
        app.busy = false;
        app.attached = Some(AttachContext {
            target: app.selected.clone().unwrap(),
            java: PathBuf::new(),
            agent: PathBuf::new(),
            support: PathBuf::new(),
            game: PathBuf::from(&app.game),
            release: "fixture".into(),
            catalog_sha256: String::new(),
            sha256: vec![],
        });
        app.phase = "inactive".into();
        app.select_profile(1);
        app.select_process(1);
        assert_eq!(app.game, original_game);
        assert_eq!(app.selected.as_ref().unwrap().pid, 100);
    }

    #[test]
    fn one_action_tracks_loading_active_stopping_and_clean_reuse() {
        assert_eq!(main_action(false, "idle", false), MainAction::Load);
        assert_eq!(
            main_action(false, "authenticating", true),
            MainAction::Waiting
        );
        assert_eq!(main_action(true, "starting", false), MainAction::Stop);
        assert_eq!(main_action(true, "active", false), MainAction::Stop);
        assert_eq!(main_action(true, "stopping", true), MainAction::Waiting);
        assert_eq!(main_action(true, "stopping", false), MainAction::Waiting);
        assert_eq!(main_action(true, "stopped", false), MainAction::Load);
        assert_eq!(main_action(true, "inactive", false), MainAction::Load);
        assert_eq!(main_action(true, "failed", false), MainAction::Stop);
        assert_eq!(
            main_action(true, "restart required", false),
            MainAction::Restart
        );
    }

    #[test]
    fn repeated_report_cycles_keep_exact_selection_and_context() {
        let mut app = fixture_app();
        app.select_profile(1);
        app.select_process(1);
        let context = AttachContext {
            target: app.selected.clone().unwrap(),
            java: PathBuf::from(r"C:\jdk21\bin\java.exe"),
            agent: PathBuf::from(r"C:\support\bootstrap-a.jar"),
            support: PathBuf::from(r"C:\support"),
            game: PathBuf::from(&app.game),
            release: "same-release".into(),
            catalog_sha256: "c".repeat(64),
            sha256: vec![7; 32],
        };
        app.attached = Some(context.clone());
        for _ in 0..3 {
            for phase in ["starting", "active", "stopping", "stopped"] {
                app.busy = true;
                app.checking = true;
                let report: Report =
                    serde_json::from_value(json!({"phase":phase,"release":"same-release"}))
                        .unwrap();
                app.tx.send(Event::Report(report)).unwrap();
                app.events();
                assert_eq!(app.phase, phase);
                assert!(!app.busy && !app.checking);
                assert_eq!(app.game, context.game.display().to_string());
                let retained = app.attached.as_ref().unwrap();
                assert_eq!(retained.target.pid, context.target.pid);
                assert_eq!(retained.target.started, context.target.started);
                assert_eq!(retained.java, context.java);
                assert_eq!(retained.agent, context.agent);
                assert_eq!(retained.sha256, context.sha256);
                assert_eq!(retained.catalog_sha256, context.catalog_sha256);
                assert_eq!(can_start(false, true, phase, true), phase == "stopped");
            }
        }
        app.select_profile(0);
        app.select_process(0);
        assert_eq!(app.game, context.game.display().to_string());
        assert_eq!(app.selected.as_ref().unwrap().pid, context.target.pid);
    }

    #[test]
    fn refreshing_processes_keeps_creation_identity_and_drops_reused_pid() {
        let mut app = fixture_app();
        app.select_process(1);
        let mut retained = app.processes[1].clone();
        retained.title = "Minecraft updated title".into();
        app.replace_processes(vec![retained.clone()]);
        assert_eq!(app.selected.as_ref().unwrap().title, retained.title);
        retained.started += 1;
        app.replace_processes(vec![retained]);
        assert!(app.selected.is_none());
    }

    #[test]
    fn automatic_polling_stops_after_confirmed_cleanup_or_failure() {
        assert_eq!(poll_interval("starting"), Some(Duration::from_secs(1)));
        assert_eq!(poll_interval("stopping"), Some(Duration::from_secs(1)));
        assert_eq!(poll_interval("active"), Some(Duration::from_secs(4)));
        for phase in ["idle", "stopped", "inactive", "failed", "restart required"] {
            assert_eq!(poll_interval(phase), None);
        }
    }

    #[test]
    fn request_failure_releases_busy_state_and_exposes_details() {
        let mut app = fixture_app();
        app.busy = true;
        app.checking = true;
        app.tx
            .send(Event::Failure("fixture failure".into()))
            .unwrap();
        app.events();
        assert!(!app.busy && !app.checking);
        assert!(app.details_open);
        assert_eq!(app.phase, "failed");
        assert_eq!(app.error, "fixture failure");
    }
    fn fixture_app() -> App {
        let (tx, rx) = mpsc::channel();
        App {
            setup_ready: true,
            setup_busy: false,
            checking: false,
            details_open: false,
            license: String::new(),
            game: String::new(),
            profiles: vec![
                GameProfile {
                    name: "First".into(),
                    game: PathBuf::from(r"C:\profiles\first"),
                },
                GameProfile {
                    name: "QA".into(),
                    game: PathBuf::from(r"C:\profiles\qa"),
                },
            ],
            processes: vec![
                MinecraftProcess {
                    pid: 100,
                    title: "Minecraft first".into(),
                    started: 1,
                },
                MinecraftProcess {
                    pid: 200,
                    title: "Minecraft QA".into(),
                    started: 2,
                },
            ],
            selected: None,
            jdk: String::new(),
            busy: false,
            phase: "idle".into(),
            status: String::new(),
            error: String::new(),
            attached: None,
            rx,
            tx,
            polled: Instant::now(),
        }
    }

    #[test]
    fn first_setup_blocks_game_selection_and_load_until_verified() {
        let mut app = fixture_app();
        app.setup_ready = false;
        app.license = "fixture-complete-looking-license-key".into();
        app.select_profile(0);
        app.select_process(0);
        app.start();
        assert!(app.game.is_empty());
        assert!(app.selected.is_none());
        assert!(!app.busy && app.attached.is_none());
        assert!(app.rx.try_recv().is_err());
        app.tx.send(Event::Activated).unwrap();
        app.events();
        assert!(app.setup_ready && !app.setup_busy);
        assert!(app.license.is_empty());
        app.select_profile(0);
        app.select_process(0);
        assert!(!app.game.is_empty() && app.selected.is_some());
    }

    #[test]
    fn failed_or_revoked_activation_keeps_the_key_screen_and_allows_retry() {
        let mut app = fixture_app();
        app.setup_ready = false;
        app.setup_busy = true;
        app.tx
            .send(Event::ActivationFailed("offline fixture".into()))
            .unwrap();
        app.events();
        assert!(!app.setup_ready && !app.setup_busy);
        assert_eq!(app.error, "offline fixture");
        app.tx.send(Event::Activated).unwrap();
        app.events();
        assert!(app.setup_ready && app.error.is_empty());
        app.busy = true;
        app.tx
            .send(Event::ActivationRequired("revoked fixture".into()))
            .unwrap();
        app.events();
        assert!(!app.setup_ready && !app.busy && !app.checking);
        assert!(app.attached.is_none());
    }

    #[test]
    #[ignore = "manual setup-window fixture; no authentication, license, or game access"]
    fn preview_first_setup_window() {
        eframe::run_native(
            "Celeste+ setup preview",
            eframe::NativeOptions {
                viewport: egui::ViewportBuilder::default().with_inner_size([440.0, 380.0]),
                ..Default::default()
            },
            Box::new(|cc| {
                apply_theme(&cc.egui_ctx);
                let mut app = fixture_app();
                app.setup_ready = false;
                Ok(Box::new(app))
            }),
        )
        .unwrap();
    }

    #[test]
    fn reload_requires_confirmed_inactive_or_stopped_and_keeps_failures_closed() {
        assert!(can_start(false, false, "failed", true));
        assert!(can_start(false, true, "inactive", true));
        assert!(can_start(false, true, "stopped", true));
        assert!(!can_start(true, true, "inactive", true));
        assert!(!can_start(false, true, "inactive", false));
        for phase in [
            "starting",
            "active",
            "stopping",
            "failed",
            "restart required",
        ] {
            assert!(!can_start(false, true, phase, true));
        }
    }
}

fn main() -> Result<()> {
    #[cfg(debug_assertions)]
    if std::env::args_os().any(|arg| arg == "--native-guard-probe") {
        debug_guard::require_clear().map_err(|_| anyhow!("native_guard_rejected"))?;
        println!("native_guard_ready");
        return Ok(());
    }
    if debug_guard::require_clear().is_err() {
        return Ok(());
    }
    if bootstrap_cache::dispatch_cleanup()? {
        return Ok(());
    }
    let _ = bootstrap_cache::cleanup_stale();
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("Celeste+")
        .with_inner_size([440.0, 380.0])
        .with_min_inner_size([400.0, 340.0])
        .with_resizable(true);
    if let Ok(source) = image::load_from_memory(include_bytes!("../assets/celeste-logo.png")) {
        let source = source.into_rgba8();
        viewport = viewport.with_icon(egui::IconData {
            width: source.width(),
            height: source.height(),
            rgba: source.into_raw(),
        });
    }
    let options = eframe::NativeOptions {
        viewport,
        centered: true,
        persist_window: false,
        ..Default::default()
    };
    let result = eframe::run_native(
        "Celeste+",
        options,
        Box::new(|cc| Ok(Box::new(App::new(cc)))),
    )
    .map_err(|error| anyhow!("Native beta window could not start: {error}"));
    let _ = bootstrap_cache::cleanup_stale();
    result
}
