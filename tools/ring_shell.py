#!/usr/bin/env python3
"""
ring_shell — a stateful interactive shell over the Claude Bridge.

Run:  python3 -i ring_shell.py
A live `ring` object is created with a background poller that continuously
ingests the ring's BLE stream and maintains a state model. Drive it with
ring.summary(), ring.tail(), ring.enable(), ring.read('00002a29'), etc.
"""
import urllib.request, json, threading, time
from collections import deque, Counter

BASE = "http://127.0.0.1:8787"

CHAN = {0x0a:'CH10-Health',0x0b:'CH11-Settings',0x0c:'CH12-Debug',0x14:'CH20-Log',
        0x15:'CH21-FOTA',0x16:'CH22-Gesture',0x17:'CH23-Heartbeat',0x1f:'CH31-Find',0x20:'CH32-Text'}

def _get(path):
    with urllib.request.urlopen(BASE+path, timeout=5) as r:
        return json.load(r)

def _post(path, data=None):
    body = json.dumps(data).encode() if data else b''
    req = urllib.request.Request(BASE+path, data=body, method='POST',
                                 headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.load(r)

def decode(hexstr):
    """Map a wire frame to (channel_label, human_detail)."""
    b = [int(x,16) for x in hexstr.split()]
    if len(b) >= 3 and b[0] == b[1] and b[0] in CHAN:
        ch, h = CHAN[b[0]], b[2]
        if b[0]==0x16 and h==0x02: return ch, f"PINCH #{b[3]}"
        if b[0]==0x16 and h==0x40: return ch, "gesture enable-ACK"
        if b[0]==0x16 and h==0x41: return ch, "gesture disable-ACK"
        if b[0]==0x0b and (h & 0x3f)==0x02:
            lvl=chg=None; i=3
            while i+1 < len(b):
                if b[i]==5: lvl=b[i+1]
                if b[i]==6: chg=bool(b[i+1])
                i+=2
            return ch, f"battery {lvl}% charging={chg}"
        if b[0]==0x0b and h==52:
            wear=None; i=3
            while i+1 < len(b):
                if b[i]==55: wear=bool(b[i+1])
                i+=2
            return ch, f"wearing={wear}"
        if b[0]==0x17: return ch, "heartbeat"
        return ch, hexstr
    if len(b) >= 2:  # standard HR Measurement char
        return "raw", hexstr
    return "raw", hexstr

class Ring:
    def __init__(self):
        self.events = deque(maxlen=8000)
        self.cursor = 0
        self.battery = None; self.charging = None
        self.gestures = None
        self.pinch_count = 0; self.last_pinch = None
        self.last_hr = None; self.last_heartbeat = None
        self.wearing = None
        self.device = {}
        self.chan_counts = Counter()
        self._stop = False
        self._lock = threading.Lock()
        self._t = threading.Thread(target=self._loop, daemon=True)
        self._t.start()

    def _loop(self):
        while not self._stop:
            try:
                d = _get(f"/events?since={self.cursor}")
                for e in d.get('events', []):
                    self._ingest(e)
                self.cursor = d.get('nextSince', self.cursor)
            except Exception:
                pass
            time.sleep(1.5)

    def _ingest(self, e):
        hexstr = e['hex']; b=[int(x,16) for x in hexstr.split()]
        label, detail = decode(hexstr)
        with self._lock:
            self.events.append((e['i'], e['t'], e['char'], label, detail, hexstr))
            self.chan_counts[label] += 1
            if detail.startswith('PINCH'):
                self.pinch_count += 1; self.last_pinch = e['t']
            elif 'battery' in detail:
                i=3
                while i+1 < len(b):
                    if b[i]==5: self.battery=b[i+1]
                    if b[i]==6: self.charging=bool(b[i+1])
                    i+=2
            elif label == 'CH23-Heartbeat':
                self.last_heartbeat = e['t']
            elif 'wearing=' in detail:
                self.wearing = detail.split('=')[1] == 'True'
            elif e['char'] == '00002a37' and len(b) >= 2:
                self.last_hr = b[1]

    # ---- commands ----
    def connect(self):   return _post('/connect')
    def disconnect(self): return _post('/disconnect')
    def enable(self):    self.gestures=True;  return _post('/gestures/enable')
    def disable(self):   self.gestures=False; return _post('/gestures/disable')
    def write(self, hexstr): return _post('/write', {'hex': hexstr})
    def read(self, uuid):    return _get(f'/read?uuid={uuid}')
    def battery_sync(self):  return _post('/battery/sync')
    def gatt(self):      print(_get('/gatt')['dump'])
    def lowpower(self):  return _post('/connpriority', {'priority':'low'})

    def identify(self):
        """Read static device-info characteristics into self.device."""
        targets = {'00002a29':'manufacturer','00002a00':'device_name','00002a01':'appearance'}
        for u, name in targets.items():
            try:
                r = self.read(u)
                if r.get('found'): self.device[name] = r.get('ascii')
            except Exception: pass
        return self.device

    def tail(self, n=20):
        with self._lock:
            for i,t,char,label,detail,hx in list(self.events)[-n:]:
                print(f"  {label:16} {detail:28} [{hx}]")

    def summary(self):
        try: connected = _get('/status').get('connected')
        except Exception: connected = '??'
        print("── RING STATE ───────────────────────────────")
        print(f"  connected   : {connected}")
        print(f"  battery     : {self.battery}%   charging={self.charging}")
        print(f"  gestures    : {self.gestures}")
        print(f"  wearing     : {self.wearing}")
        print(f"  pinches     : {self.pinch_count}   lastHR={self.last_hr} bpm")
        print(f"  events seen : {len(self.events)}  ", dict(self.chan_counts))
        if self.device: print(f"  device      : {self.device}")
        print("─────────────────────────────────────────────")

    def model(self):
        return dict(connected_poll=True, battery=self.battery, charging=self.charging,
                    gestures=self.gestures, wearing=self.wearing, pinches=self.pinch_count,
                    last_hr=self.last_hr, events=len(self.events),
                    channels=dict(self.chan_counts), device=self.device)

ring = Ring()
time.sleep(0.5)
try:
    if not _get('/status').get('connected'):
        ring.connect(); time.sleep(5)
except Exception as ex:
    print("bridge not reachable:", ex)
ring.identify()
print(">>> ring shell ready — `ring` is live and polling. Try ring.summary()")
