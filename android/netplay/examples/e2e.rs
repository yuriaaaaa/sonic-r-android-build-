//! Desktop check of the Android bridge against a real hub:
//!   e2e host <hub>        registers a room; a fake game answers on 127.0.0.1:5029
//!   e2e hubhost <hub>     the same, running the hub in-process first (hub = 127.0.0.1)
//!   e2e join <hub> <id>   joins it and sends a packet through the tunnel
use std::net::UdpSocket;
use std::time::{Duration, Instant};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let hub = args[2].clone();
    match args[1].as_str() {
        "list" => println!("{}", sonicrnetplay::list_rooms(&hub)),
        "host" | "hubhost" => {
            if args[1] == "hubhost" {
                if let Some(e) = sonicrnetplay::start_hub() { panic!("hub: {e}"); }
                println!("hub started");
            }
            // Fake game lobby: answers discovery and echoes everything else.
            let game = UdpSocket::bind("127.0.0.1:5029").unwrap();
            std::thread::spawn(move || {
                let mut buf = [0u8; 2048];
                loop {
                    let (n, from) = game.recv_from(&mut buf).unwrap();
                    if n >= 4 && u32::from_le_bytes(buf[..4].try_into().unwrap()) == 0x534F4E52 {
                        game.send_to(&0x534F4E48u32.to_le_bytes(), from).unwrap();
                    } else {
                        println!("GAME got {} bytes: {:?}", n, String::from_utf8_lossy(&buf[..n]));
                        game.send_to(&buf[..n], from).unwrap();
                    }
                }
            });
            sonicrnetplay::start_host(hub, "Android test room".into(), 5029);
            pump(Duration::from_secs(40));
        }
        "join" => {
            sonicrnetplay::start_join(hub, args[3].clone(), 5030);
            let start = Instant::now();
            let mut proxy = None;
            while start.elapsed() < Duration::from_secs(20) {
                while let Some(e) = sonicrnetplay::poll_event() {
                    println!("JOIN event {e}");
                    if e.contains("\"proxy\"") { proxy = Some(()); }
                }
                if proxy.is_some() { break; }
                std::thread::sleep(Duration::from_millis(100));
            }
            let s = UdpSocket::bind("127.0.0.1:0").unwrap();
            s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            for i in 0..5 {
                s.send_to(format!("hello {i}").as_bytes(), "127.0.0.1:5030").unwrap();
                let mut buf = [0u8; 2048];
                match s.recv_from(&mut buf) {
                    Ok((n, _)) => { println!("JOIN echo: {}", String::from_utf8_lossy(&buf[..n])); break; }
                    Err(e) => println!("JOIN no echo yet: {e}"),
                }
            }
            pump(Duration::from_secs(3));
        }
        _ => {}
    }
}

fn pump(d: Duration) {
    let start = Instant::now();
    while start.elapsed() < d {
        while let Some(e) = sonicrnetplay::poll_event() {
            println!("event {e}");
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
