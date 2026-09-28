fn main() {
    println!("cargo:rerun-if-changed=app.manifest");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let manifest = std::path::Path::new(&std::env::var_os("CARGO_MANIFEST_DIR").unwrap())
            .join("app.manifest");
        println!("cargo:rustc-link-arg-bin=CelestePlusSetup=/MANIFEST:EMBED");
        println!(
            "cargo:rustc-link-arg-bin=CelestePlusSetup=/MANIFESTINPUT:{}",
            manifest.display()
        );
    }
}
