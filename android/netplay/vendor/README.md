# Vendored netplay crates

`proto/` and `sidecar/` are copied from
[ZinexDa/sonic-r-netplay](https://github.com/ZinexDa/sonic-r-netplay) at commit
838e036 (v1.5), under its licence (`LICENSE`: free to use, build and change; not
to be sold).

One change for Android: `sidecar` uses `rustls-tls-webpki-roots` instead of
`rustls-tls-native-roots`, because Android's certificate store can't be read
by rustls-native-certs, which `wss://` hubs need.
