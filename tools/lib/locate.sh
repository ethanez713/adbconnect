# locate.sh -- find the adbconnect app's listener on the LAN. Sourced by adbwake and adbpush.
#
# The app's listener is the one thing on the phone that can be found by scanning: fixed port,
# always up. adbd cannot be -- wireless debugging binds a random high port, and nothing at all
# when it is off. So both tools that need to reach the phone share this discovery.
#
# Contract the caller must set before sourcing: PORT, PROBE_TIMEOUT, CONFIG, DEVICE_STATE, and
# log()/err() functions.

port_open() {
  timeout "$PROBE_TIMEOUT" bash -c "exec 3<>/dev/tcp/$1/$PORT" 2>/dev/null
}

# Keyed off the default-route interface: WSL also carries a scope-global
# 10.255.255.254/32 alias on lo, which otherwise wins and yields a dead subnet.
lan_prefix() {
  local dev
  dev="$(ip -4 route show default 2>/dev/null \
         | awk '{for (i=1; i<NF; i++) if ($i=="dev") {print $(i+1); exit}}')"
  ip -4 -o addr show ${dev:+dev "$dev"} scope global 2>/dev/null \
    | awk '$2!="lo" && $4 !~ /\/3[12]$/ {print $4; exit}' \
    | cut -d/ -f1 | sed 's/\.[0-9]*$//'
}

# Every dotted-quad the phone has ever been seen at: the saved hint, plus every
# ip:port aconnect remembers. Service names (no colon) drop out. This is what
# lets a sweep reach a phone the router has put on a different /24 than the PC.
remembered_ips() {
  { sed -n 's/^IP=//p' "$CONFIG" 2>/dev/null
    sed -n 's/:.*//p' "$DEVICE_STATE" 2>/dev/null
  } | grep -E '^[0-9]+(\.[0-9]+){3}$' | awk 'NF && !seen[$0]++'
}

# The /24s worth sweeping: this laptop's own, plus every one the phone has lived
# on. On a single-/24 home network these collapse to one; on a network that
# splits phone and PC across subnets, the phone's historical /24 is the one the
# old single-subnet sweep could never see.
sweep_prefixes() {
  { lan_prefix; remembered_ips | sed 's/\.[0-9]*$//'; } | awk 'NF && !seen[$0]++'
}

sweep_for_listener() {
  local prefixes; prefixes="$(sweep_prefixes)"
  [ -n "$prefixes" ] || { err "no default route and nothing remembered; no subnet to sweep"; return 1; }
  local hits; hits="$(mktemp)"
  local prefix i batch=0
  for prefix in $prefixes; do
    log "sweeping ${prefix}.0/24 for the app's listener on $PORT"
    for i in $(seq 1 254); do
      # A sub-second timeout here silently misses the phone: Wi-Fi power-save
      # delays its first SYN-ACK well past what a mains-powered host needs, so the
      # one device the sweep exists to find is the one it drops. Cheap to be
      # generous -- the whole subnet is probed in parallel, so this is the wall
      # time of the sweep, not the per-host cost.
      ( timeout "$PROBE_TIMEOUT" bash -c "exec 3<>/dev/tcp/${prefix}.${i}/${PORT}" 2>/dev/null \
          && printf '%s\n' "${prefix}.${i}" >> "$hits" ) &
      batch=$(( batch + 1 ))
      [ "$batch" -ge 128 ] && { wait; batch=0; }
    done
  done
  wait
  sort -u "$hits" 2>/dev/null
  rm -f "$hits"
}
