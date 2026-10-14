//! JNI bridge to sonic-r-netplay's sidecar for the Android launcher
//! (com.yuriaaaaa.sonicr.Netplay). The sidecar registers or joins a room on a
//! netplay hub and tunnels the game's UDP traffic (127.0.0.1:<port>) to the
//! other player, directly or through the hub's relay.
//!
//! One session runs at a time on a shared tokio runtime. Its progress is queued
//! as small JSON events that Java polls with nativePollEvent.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde_json::json;
use sidecar::runner::{self, HostConfig, JoinConfig, RunnerEvent};
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

const HUB_UDP_PORT: u16 = 9000;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static EVENTS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static SESSION: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

fn push(event: serde_json::Value) {
    if let Ok(mut q) = EVENTS.lock() {
        if q.len() > 256 {
            q.pop_front();
        }
        q.push_back(event.to_string());
    }
}

/// Splits what the player typed ("host:8080", "ws://host:8080/ws", "wss://name")
/// into the WebSocket URL and the host name, as the desktop launcher does.
fn hub_parts(raw: &str) -> (String, String) {
    let trimmed = raw.trim();
    let (scheme, rest) = if let Some(s) = trimmed.strip_prefix("wss://") {
        ("wss://", s)
    } else if let Some(s) = trimmed.strip_prefix("ws://") {
        ("ws://", s)
    } else {
        ("ws://", trimmed)
    };
    let (authority, path) = match rest.find('/') {
        Some(i) => {
            let (a, p) = rest.split_at(i);
            (a, if p == "/" { "/ws".to_string() } else { p.to_string() })
        }
        None => (rest, "/ws".to_string()),
    };
    // A bare host name gets the hub's default WebSocket port.
    let authority = if scheme == "ws://" && !authority.contains(':') {
        format!("{authority}:8080")
    } else {
        authority.to_string()
    };
    let host = authority.split(':').next().unwrap_or("").to_string();
    (format!("{scheme}{authority}{path}"), host)
}

async fn hub_udp(host: &str) -> Result<SocketAddr, String> {
    let target = format!("{host}:{HUB_UDP_PORT}");
    let addrs: Vec<SocketAddr> = tokio::net::lookup_host(&target)
        .await
        .map_err(|e| format!("Can't find the hub {host}: {e}"))?
        .collect();
    addrs
        .iter()
        .find(|a| a.is_ipv4())
        .or_else(|| addrs.first())
        .copied()
        .ok_or_else(|| format!("Can't find the hub {host}"))
}

fn event_json(e: RunnerEvent) -> serde_json::Value {
    match e {
        RunnerEvent::Status(text) => json!({"type": "status", "text": text}),
        RunnerEvent::Registered { server_id } => json!({"type": "registered", "id": server_id.to_string()}),
        RunnerEvent::PeerCandidateReceived { peer_addr, .. } => {
            json!({"type": "peer", "addr": peer_addr.to_string()})
        }
        RunnerEvent::TunnelEstablished { peer_addr, is_relay } => {
            json!({"type": "tunnel", "addr": peer_addr.to_string(), "relay": is_relay})
        }
        RunnerEvent::ProxyBound { port } => json!({"type": "proxy", "port": port}),
        RunnerEvent::Error(text) => json!({"type": "error", "text": text}),
        RunnerEvent::Stopped => json!({"type": "stopped"}),
    }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn stop_session() {
    if let Ok(mut s) = SESSION.lock() {
        if let Some(task) = s.take() {
            task.abort();
        }
    }
    if let Ok(mut q) = EVENTS.lock() {
        q.clear();
    }
}

/// Runs a host or join session, forwarding its events to the queue.
fn start_session<F, Fut>(run: F)
where
    F: FnOnce(tokio::sync::mpsc::Sender<RunnerEvent>) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<(), String>> + Send + 'static,
{
    stop_session();
    let task = runtime().spawn(async move {
        let (tx, mut rx) = tokio::sync::mpsc::channel(64);
        let forward = tokio::spawn(async move {
            while let Some(e) = rx.recv().await {
                push(event_json(e));
            }
        });
        let result = run(tx).await;
        // Let the last events through; tasks inside the runner may still hold senders.
        tokio::time::sleep(Duration::from_millis(200)).await;
        forward.abort();
        match result {
            Ok(()) => push(json!({"type": "stopped"})),
            Err(text) => push(json!({"type": "error", "text": text})),
        }
    });
    if let Ok(mut s) = SESSION.lock() {
        *s = Some(task);
    }
}

/// Lists the hub's rooms: {"rooms":[{id,name,players,max,joinable,version}]} or {"error":"..."}.
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativeListRooms(
    mut env: JNIEnv,
    _class: JClass,
    hub: JString,
) -> jstring {
    let hub = get_string(&mut env, &hub);
    let result = catch_unwind(AssertUnwindSafe(|| list_rooms(&hub)))
        .unwrap_or_else(|_| json!({"error": "Netplay crashed while listing rooms"}));
    new_string(&mut env, &result.to_string())
}

pub fn list_rooms(hub: &str) -> serde_json::Value {
        let (ws, _) = hub_parts(hub);
        runtime().block_on(async {
            match tokio::time::timeout(Duration::from_secs(10), runner::fetch_server_list(&ws)).await {
                Ok(Ok(servers)) => {
                    let rooms: Vec<_> = servers
                        .iter()
                        .map(|s| {
                            json!({
                                "id": s.id.to_string(),
                                "name": s.name,
                                "players": s.players,
                                "max": s.max_players,
                                "joinable": s.is_joinable(),
                                "version": s.game_version,
                            })
                        })
                        .collect();
                    json!({"rooms": rooms})
                }
                Ok(Err(e)) => json!({"error": e}),
                Err(_) => json!({"error": "The hub didn't answer"}),
            }
        })
}

/// Registers a room on the hub and forwards players to the game's lobby on 127.0.0.1:gamePort.
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativeStartHost(
    mut env: JNIEnv,
    _class: JClass,
    hub: JString,
    name: JString,
    game_port: jint,
) {
    let hub = get_string(&mut env, &hub);
    let name = get_string(&mut env, &name);
    let _ = catch_unwind(AssertUnwindSafe(|| start_host(hub, name, game_port as u16)));
}

pub fn start_host(hub: String, name: String, port: u16) {
        start_session(move |tx| async move {
            let (ws, host) = hub_parts(&hub);
            let udp = hub_udp(&host).await?;
            let config = HostConfig {
                hub_ws_url: ws,
                hub_udp_addr: udp,
                name,
                bind_port: 0,
                target_game_addr: Some(SocketAddr::from(([127, 0, 0, 1], port))),
            };
            runner::run_host_session(config, Some(tx)).await
        });
}

/// Joins a room; the game then connects to the local proxy on 127.0.0.1:bindPort.
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativeStartJoin(
    mut env: JNIEnv,
    _class: JClass,
    hub: JString,
    server_id: JString,
    bind_port: jint,
) {
    let hub = get_string(&mut env, &hub);
    let id = get_string(&mut env, &server_id);
    let _ = catch_unwind(AssertUnwindSafe(|| start_join(hub, id, bind_port as u16)));
}

pub fn start_join(hub: String, id: String, port: u16) {
        start_session(move |tx| async move {
            let server_id = uuid::Uuid::parse_str(id.trim()).map_err(|_| "That room id isn't valid".to_string())?;
            let (ws, host) = hub_parts(&hub);
            let udp = hub_udp(&host).await?;
            let config = JoinConfig {
                hub_ws_url: ws,
                hub_udp_addr: udp,
                server_id: Some(server_id),
                bind_port: port,
                target_game_addr: None,
            };
            runner::run_join_session(config, Some(tx)).await
        });
}

static HUB_RUNNING: Mutex<bool> = Mutex::new(false);

/// Runs a hub on this device (TCP 8080, UDP 9000 and 9001), so the host needs
/// no PC. It lasts as long as the process. Returns an error message, or None.
pub fn start_hub() -> Option<String> {
    let mut running = HUB_RUNNING.lock().ok()?;
    if *running {
        return None;
    }
    let result = runtime().block_on(async {
        let state = hub::state::AppState::new_dynamic();
        let punch = tokio::net::UdpSocket::bind("0.0.0.0:9000")
            .await
            .map_err(|e| format!("Port 9000 is busy: {e}"))?;
        drop(punch); // run_punch_listener binds it itself; this only checks it's free
        let relay = tokio::net::UdpSocket::bind("0.0.0.0:9001")
            .await
            .map_err(|e| format!("Port 9001 is busy: {e}"))?;
        let listener = tokio::net::TcpListener::bind("0.0.0.0:8080")
            .await
            .map_err(|e| format!("Port 8080 is busy: {e}"))?;
        tokio::spawn(hub::punch::run_punch_listener(
            SocketAddr::from(([0, 0, 0, 0], 9000)),
            state.clone(),
        ));
        tokio::spawn(hub::relay::run_relay_listener(relay, state.clone()));
        let reaper = state.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(5));
            loop {
                tick.tick().await;
                reaper.reap_stale();
            }
        });
        let app = hub::create_router(state);
        tokio::spawn(async move {
            let _ = axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await;
        });
        Ok::<(), String>(())
    });
    match result {
        Ok(()) => {
            *running = true;
            None
        }
        Err(e) => Some(e),
    }
}

/// Starts the on-device hub; returns null on success or an error message.
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativeStartHub(mut env: JNIEnv, _class: JClass) -> jstring {
    let result = catch_unwind(start_hub).unwrap_or_else(|_| Some("The hub crashed while starting".to_string()));
    match result {
        Some(e) => new_string(&mut env, &e),
        None => std::ptr::null_mut(),
    }
}

/// Next queued event as JSON, or null when there is none.
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativePollEvent(mut env: JNIEnv, _class: JClass) -> jstring {
    match poll_event() {
        Some(e) => new_string(&mut env, &e),
        None => std::ptr::null_mut(),
    }
}

pub fn poll_event() -> Option<String> {
    EVENTS.lock().ok().and_then(|mut q| q.pop_front())
}

/// Ends the current session (the hub drops the room when the connection closes).
#[no_mangle]
pub extern "system" fn Java_com_yuriaaaaa_sonicr_Netplay_nativeStop(_env: JNIEnv, _class: JClass) {
    let _ = catch_unwind(stop_session);
}

#[cfg(test)]
mod tests {
    use super::hub_parts;

    #[test]
    fn hub_addresses() {
        assert_eq!(hub_parts("1.2.3.4").0, "ws://1.2.3.4:8080/ws");
        assert_eq!(hub_parts("1.2.3.4:8080").0, "ws://1.2.3.4:8080/ws");
        assert_eq!(hub_parts("ws://h:9/x").0, "ws://h:9/x");
        assert_eq!(hub_parts("wss://hub.example.com").0, "wss://hub.example.com/ws");
        assert_eq!(hub_parts("wss://hub.example.com/").1, "hub.example.com");
    }
}
