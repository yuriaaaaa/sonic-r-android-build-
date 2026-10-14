# Running a netplay hub

Online rooms need a hub: a small server that lists rooms, introduces players
and relays their traffic when a direct connection isn't possible. It's the
`hub` from [ZinexDa/sonic-r-netplay](https://github.com/ZinexDa/sonic-r-netplay).
A phone can't run it, because mobile networks don't accept incoming
connections. Run it on a PC or a cloud server that stays on while you play.

The hub uses three ports:

| Port | Protocol | Used for |
|---|---|---|
| 8080 | TCP | Room list and joining (WebSocket) |
| 9000 | UDP | Finding each player's public address |
| 9001 | UDP | Relay when a direct connection fails |

## On a cloud server (works from anywhere)

1. Create a small Linux server with a public IP (a free tier is enough).
2. Allow TCP 8080 and UDP 9000-9001 in its firewall or security group.
3. Build and start the hub (needs [Rust](https://rustup.rs)):
   ```sh
   git clone https://github.com/ZinexDa/sonic-r-netplay
   cd sonic-r-netplay
   cargo build --release -p hub
   ./target/release/hub --relay-public-addr <server-ip>:9001
   ```
4. In the app, enter `<server-ip>` as the hub address.

## On a home PC

1. Build the hub as above, or take `hub.exe` from a sonic-r-netplay release,
   and run it.
2. On your router, forward TCP 8080 and UDP 9000-9001 to that PC, and allow
   them through its firewall.
3. Players outside your home enter your public IP as the hub address. Phones
   on your own Wi-Fi can use the PC's local IP instead.

## Hub address formats

`203.0.113.5` and `203.0.113.5:8080` both mean `ws://203.0.113.5:8080/ws`.
A hub behind TLS is entered as `wss://hub.example.com`. A public IP with the
three ports open is the most reliable setup.
