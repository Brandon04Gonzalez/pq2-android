//! links librecomp.a, Persona Q2's code that 3dsrecomp port compiled for the
//! APK's architecture, PQ2_RECOMP_DIR saying where it is when it is not in
//! the port's own folder.

fn main() {
    // only the developer's builds link the game's code (the linked feature)
    println!("cargo:rerun-if-env-changed=CARGO_FEATURE_LINKED");
    if std::env::var_os("CARGO_FEATURE_LINKED").is_none() {
        return;
    }
    let arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap();
    let dir = std::env::var("PQ2_RECOMP_DIR").unwrap_or_else(|_| {
        let port = match arch.as_str() {
            "aarch64" => "port_arm64",
            "x86_64" => "port_x86_64",
            other => panic!("there is no recompiled code for {other}"),
        };
        format!("{}/../{port}/code", std::env::var("CARGO_MANIFEST_DIR").unwrap())
    });
    println!("cargo:rerun-if-changed={dir}/librecomp.a");
    println!("cargo:rerun-if-env-changed=PQ2_RECOMP_DIR");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=static=recomp");
}
