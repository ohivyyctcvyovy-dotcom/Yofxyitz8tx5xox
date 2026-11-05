use flate2::read::GzDecoder;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use translator::{DownloadEncoding, Feature, FsPackInstallChecker, StageTarget, TaskInstall, TranslatorSession};

static SESSION: OnceLock<Mutex<Option<TranslatorSession>>> = OnceLock::new();

fn session() -> &'static Mutex<Option<TranslatorSession>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

fn java_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value).map(|s| s.into()).map_err(|e| e.to_string())
}

fn download_to(url: &str, path: &Path, encoding: DownloadEncoding) -> Result<(), String> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
    let response = ureq::get(url).call().map_err(|e| format!("Download failed: {e}"))?;
    let mut reader: Box<dyn Read> = Box::new(response.into_reader());
    if encoding == DownloadEncoding::Gzip { reader = Box::new(GzDecoder::new(reader)); }
    let mut out = File::create(path).map_err(|e| e.to_string())?;
    std::io::copy(&mut reader, &mut out).map_err(|e| e.to_string())?;
    out.flush().map_err(|e| e.to_string())
}

fn extract_zip(archive: &Path, destination: &Path) -> Result<(), String> {
    let file = File::open(archive).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;
    let root = destination.canonicalize().or_else(|_| { fs::create_dir_all(destination)?; destination.canonicalize() }).map_err(|e| e.to_string())?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
        let Some(relative) = entry.enclosed_name().map(PathBuf::from) else { return Err("Invalid ZIP entry path".into()); };
        let output = root.join(relative);
        if !output.starts_with(&root) { return Err("ZIP entry escapes destination".into()); }
        if entry.is_dir() { fs::create_dir_all(&output).map_err(|e| e.to_string())?; }
        else {
            if let Some(parent) = output.parent() { fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
            let mut target = File::create(output).map_err(|e| e.to_string())?;
            std::io::copy(&mut entry, &mut target).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

fn install_language(code: &str) -> Result<(), String> {
    let guard = session().lock().map_err(|e| e.to_string())?;
    let engine = guard.as_ref().ok_or("Translator is not initialized")?;
    let Some(plan) = engine.plan_download(code, Feature::Core, None) else {
        return Err(format!("No translation models in catalog for {code}"));
    };
    if plan.tasks.is_empty() { return Ok(()); }
    let installation = engine.begin_install(plan).map_err(|e| e.to_string())?;
    for task in installation_plan_tasks(&installation, engine, code)? {
        let target = installation.stage_target(&task).map_err(|e| e.to_string())?;
        match target {
            StageTarget::File { path, encoding } => {
                download_to(&task.url, Path::new(&path), encoding)?;
            }
            StageTarget::Zip { archive_path, unpack_dir } => {
                download_to(&task.url, Path::new(&archive_path), DownloadEncoding::Identity)?;
                extract_zip(Path::new(&archive_path), Path::new(&unpack_dir))?;
            }
        }
        installation.mark_staged(&task).map_err(|e| e.to_string())?;
    }
    engine.commit_install(&installation).map_err(|e| e.to_string())?;
    engine.refresh_snapshot();
    Ok(())
}

// Rebuild the plan from the same catalog snapshot; the session is locked for the full install.
fn installation_plan_tasks(_installation: &translator::Installation, engine: &TranslatorSession, code: &str) -> Result<Vec<translator::DownloadTask>, String> {
    engine.plan_download(code, Feature::Core, None).map(|p| p.tasks).ok_or_else(|| format!("No model plan for {code}"))
}

fn translate(from: &str, text: &str) -> Result<String, String> {
    let guard = session().lock().map_err(|e| e.to_string())?;
    let engine = guard.as_ref().ok_or("Translator is not initialized")?;
    if from == "pt" { return Ok(text.to_string()); }
    if from == "en" {
        return engine.translate_text("en", "pt", text).map_err(|e| e.to_string());
    }
    let english = engine.translate_text(from, "en", text).map_err(|e| e.to_string())?;
    engine.translate_text("en", "pt", &english).map_err(|e| e.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_telaleitor_NativeOfflineTranslator_nativeInit(
    mut env: JNIEnv, _class: JClass, index: JString, base_dir: JString,
) -> jboolean {
    let result = (|| -> Result<(), String> {
        let index = java_string(&mut env, index)?;
        let base = java_string(&mut env, base_dir)?;
        fs::create_dir_all(&base).map_err(|e| e.to_string())?;
        let checker = FsPackInstallChecker::new(&base);
        let engine = TranslatorSession::open(&index, None, base, &checker).map_err(|e| e.to_string())?;
        *session().lock().map_err(|e| e.to_string())? = Some(engine);
        Ok(())
    })();
    if result.is_ok() { 1 } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_telaleitor_NativeOfflineTranslator_nativeInstallLanguage(
    mut env: JNIEnv, _class: JClass, code: JString,
) -> jstring {
    let result = java_string(&mut env, code).and_then(|code| install_language(&code).map(|_| "OK".to_string()));
    env.new_string(result.unwrap_or_else(|e| format!("ERROR:{e}"))).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_telaleitor_NativeOfflineTranslator_nativeTranslate(
    mut env: JNIEnv, _class: JClass, from: JString, text: JString,
) -> jstring {
    let result = java_string(&mut env, from).and_then(|from| java_string(&mut env, text).and_then(|text| translate(&from, &text)));
    env.new_string(result.unwrap_or_else(|e| format!("ERROR:{e}"))).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
