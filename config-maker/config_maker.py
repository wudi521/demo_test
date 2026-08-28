import base64
import json
import os
import secrets
import sys
from datetime import datetime, timedelta
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

FORMAT = "VELAGATE-CONF-1"
SETTINGS_FILE = Path.home() / ".velagate_config_maker.json"


def app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def now_iso() -> str:
    return datetime.now().astimezone().replace(microsecond=0).isoformat()


def token8() -> str:
    return secrets.token_hex(4).upper()


def compact_json(data) -> bytes:
    return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def load_private_key(path: str):
    raw = Path(path).expanduser().read_bytes()
    return serialization.load_pem_private_key(raw, password=None)


def sign_conf(private_key, kind: str, file_id: str, payload: dict) -> dict:
    issued_at = now_iso()
    payload_b64 = base64.b64encode(compact_json(payload)).decode("ascii")
    signed = f"{FORMAT}\n{kind}\n{file_id}\n{issued_at}\n{payload_b64}".encode("utf-8")
    signature = private_key.sign(signed, padding.PKCS1v15(), hashes.SHA256())
    return {
        "format": FORMAT,
        "kind": kind,
        "fileId": file_id,
        "issuedAt": issued_at,
        "payload": payload_b64,
        "signature": base64.b64encode(signature).decode("ascii"),
    }


def save_conf(path: Path, conf: dict):
    path.write_text(json.dumps(conf, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_route_file_id(path: str) -> str:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if raw.get("format") != FORMAT or raw.get("kind") != "route" or not raw.get("fileId"):
        raise ValueError("Not a valid VelaGate route .conf")
    return str(raw["fileId"])


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("VelaGate Config Maker")
        self.geometry("920x720")
        self.minsize(820, 650)
        self.option_add("*Font", ("Arial", 11))
        self._settings = self.load_settings()
        self.last_route_id = tk.StringVar(value="")
        self.key_path = tk.StringVar(value=self.default_key_path())
        self.status = tk.StringVar(value="Ready")
        self.build_ui()
        self.seed_nodes()

    def load_settings(self):
        try:
            return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def save_settings(self):
        try:
            SETTINGS_FILE.write_text(json.dumps({"key_path": self.key_path.get()}, ensure_ascii=False), encoding="utf-8")
        except Exception:
            pass

    def default_key_path(self):
        saved = self._settings.get("key_path")
        if saved and Path(saved).expanduser().exists():
            return saved
        candidate = app_dir() / "velagate_signing_private.pem"
        return str(candidate)

    def build_ui(self):
        outer = ttk.Frame(self, padding=14)
        outer.pack(fill="both", expand=True)

        ttk.Label(outer, text="VelaGate Config Maker", font=("Arial", 20, "bold")).pack(anchor="w")
        ttk.Label(outer, text="Generate signed one-time route and traffic configuration files.").pack(anchor="w", pady=(2, 12))

        key_frame = ttk.LabelFrame(outer, text="Signing key", padding=10)
        key_frame.pack(fill="x")
        ttk.Entry(key_frame, textvariable=self.key_path).pack(side="left", fill="x", expand=True)
        ttk.Button(key_frame, text="Browse", command=self.pick_key).pack(side="left", padx=(8, 0))

        notebook = ttk.Notebook(outer)
        notebook.pack(fill="both", expand=True, pady=12)

        route_tab = ttk.Frame(notebook, padding=12)
        traffic_tab = ttk.Frame(notebook, padding=12)
        notebook.add(route_tab, text="Europe Route")
        notebook.add(traffic_tab, text="Traffic Package")

        self.build_route_tab(route_tab)
        self.build_traffic_tab(traffic_tab)

        actions = ttk.Frame(outer)
        actions.pack(fill="x")
        ttk.Button(actions, text="Generate Route", command=self.generate_route).pack(side="left")
        ttk.Button(actions, text="Generate Traffic", command=self.generate_traffic).pack(side="left", padx=8)
        ttk.Button(actions, text="Generate Matched Pair", command=self.generate_pair).pack(side="left")
        ttk.Label(actions, textvariable=self.status).pack(side="right")

    def build_route_tab(self, parent):
        fields = ttk.Frame(parent)
        fields.pack(fill="x")
        self.route_title = tk.StringVar(value="Europe Dedicated Route")
        ttk.Label(fields, text="Route title").grid(row=0, column=0, sticky="w")
        ttk.Entry(fields, textvariable=self.route_title, width=48).grid(row=0, column=1, sticky="ew", padx=(8, 0))
        fields.columnconfigure(1, weight=1)

        ttk.Label(parent, text="Nodes", font=("Arial", 12, "bold")).pack(anchor="w", pady=(14, 6))
        self.nodes = ttk.Treeview(parent, columns=("name", "ip", "port", "protocol"), show="headings", height=12)
        for col, width in [("name", 190), ("ip", 220), ("port", 100), ("protocol", 120)]:
            self.nodes.heading(col, text=col.capitalize())
            self.nodes.column(col, width=width, anchor="w")
        self.nodes.pack(fill="both", expand=True)

        edit = ttk.Frame(parent)
        edit.pack(fill="x", pady=8)
        self.node_name = tk.StringVar(value="Europe 1")
        self.node_ip = tk.StringVar(value="154.40.50.182")
        self.node_port = tk.StringVar(value="23887")
        self.node_protocol = tk.StringVar(value="VLESS")
        for i, (label, var, width) in enumerate([
            ("Name", self.node_name, 18), ("IP", self.node_ip, 20), ("Port", self.node_port, 10), ("Protocol", self.node_protocol, 12)
        ]):
            ttk.Label(edit, text=label).grid(row=0, column=i * 2, padx=(0 if i == 0 else 8, 4))
            ttk.Entry(edit, textvariable=var, width=width).grid(row=0, column=i * 2 + 1)
        ttk.Button(edit, text="Add", command=self.add_node).grid(row=0, column=8, padx=(10, 4))
        ttk.Button(edit, text="Delete selected", command=self.delete_node).grid(row=0, column=9)

        current = ttk.Frame(parent)
        current.pack(fill="x")
        ttk.Label(current, text="Last route fileId:").pack(side="left")
        ttk.Entry(current, textvariable=self.last_route_id, state="readonly").pack(side="left", fill="x", expand=True, padx=(8, 0))

    def build_traffic_tab(self, parent):
        grid = ttk.Frame(parent)
        grid.pack(fill="x")
        self.traffic_route_id = tk.StringVar(value="")
        self.package_name = tk.StringVar(value="Traffic Package 1000G")
        self.quota_gb = tk.StringVar(value="1000")
        self.traffic_ip = tk.StringVar(value="185.225.73.88")
        self.traffic_port = tk.StringVar(value="29333")
        self.traffic_protocol = tk.StringVar(value="VLESS")
        self.expires_at = tk.StringVar(value=(datetime.now().astimezone() + timedelta(days=365)).replace(microsecond=0).isoformat())

        labels = [
            ("Route fileId", self.traffic_route_id),
            ("Package name", self.package_name),
            ("Quota GB", self.quota_gb),
            ("Package IP", self.traffic_ip),
            ("Package port", self.traffic_port),
            ("Protocol", self.traffic_protocol),
            ("Expires at", self.expires_at),
        ]
        for r, (label, var) in enumerate(labels):
            ttk.Label(grid, text=label).grid(row=r, column=0, sticky="w", pady=6)
            ttk.Entry(grid, textvariable=var).grid(row=r, column=1, sticky="ew", padx=(10, 8), pady=6)
        grid.columnconfigure(1, weight=1)
        ttk.Button(grid, text="Load Route .conf", command=self.load_route_for_traffic).grid(row=0, column=2)

        note = (
            "Generate Matched Pair automatically creates a new route fileId and writes it into the traffic package.\n"
            "Each generated file has a unique fileId, so each customer should receive a newly generated pair."
        )
        ttk.Label(parent, text=note, foreground="#555").pack(anchor="w", pady=(18, 0))

    def seed_nodes(self):
        defaults = [
            ("Europe 1", "154.40.50.182", 23887, "VLESS"),
            ("Europe 2", "45.88.194.72", 31844, "VLESS"),
            ("Europe 3", "91.205.187.46", 15622, "VLESS"),
            ("Europe 4", "185.225.73.119", 24443, "VLESS"),
            ("Europe 5", "96.126.190.134", 39393, "VLESS"),
        ]
        for row in defaults:
            self.nodes.insert("", "end", values=row)

    def pick_key(self):
        path = filedialog.askopenfilename(title="Select VelaGate signing private key", filetypes=[("PEM key", "*.pem"), ("All files", "*.*")])
        if path:
            self.key_path.set(path)
            self.save_settings()

    def get_key(self):
        path = self.key_path.get().strip()
        if not path or not Path(path).expanduser().exists():
            raise FileNotFoundError("Signing key not found. Put velagate_signing_private.pem next to the app or choose it with Browse.")
        key = load_private_key(path)
        self.save_settings()
        return key

    def add_node(self):
        try:
            port = int(self.node_port.get().strip())
            if port < 1 or port > 65535:
                raise ValueError
        except Exception:
            messagebox.showerror("Invalid port", "Port must be between 1 and 65535.")
            return
        name = self.node_name.get().strip()
        ip = self.node_ip.get().strip()
        protocol = self.node_protocol.get().strip() or "VLESS"
        if not name or not ip:
            messagebox.showerror("Missing field", "Node name and IP are required.")
            return
        self.nodes.insert("", "end", values=(name, ip, port, protocol))

    def delete_node(self):
        for item in self.nodes.selection():
            self.nodes.delete(item)

    def route_payload(self):
        nodes = []
        for item in self.nodes.get_children():
            name, ip, port, protocol = self.nodes.item(item, "values")
            nodes.append({"name": name, "ip": ip, "port": int(port), "protocol": protocol or "VLESS"})
        if not nodes:
            raise ValueError("At least one route node is required.")
        return {"schema": 1, "region": "EUROPE", "title": self.route_title.get().strip() or "Europe Dedicated Route", "nodes": nodes}

    def traffic_payload(self, route_id: str):
        if not route_id:
            raise ValueError("Traffic package requires a route fileId.")
        try:
            quota = int(self.quota_gb.get().strip())
            port = int(self.traffic_port.get().strip())
        except Exception:
            raise ValueError("Quota and port must be numbers.")
        if quota <= 0 or port < 1 or port > 65535:
            raise ValueError("Invalid quota or port.")
        return {
            "schema": 1,
            "routeFileId": route_id,
            "packageName": self.package_name.get().strip() or f"Traffic Package {quota}G",
            "quotaGb": quota,
            "ip": self.traffic_ip.get().strip(),
            "port": port,
            "protocol": self.traffic_protocol.get().strip() or "VLESS",
            "expiresAt": self.expires_at.get().strip(),
        }

    def route_id(self):
        return f"VGR-EU-{datetime.now():%Y%m%d}-{token8()}"

    def traffic_id(self, quota: str):
        q = ''.join(ch for ch in quota if ch.isdigit()) or "PKG"
        return f"VGT-{q}G-{datetime.now():%Y%m%d}-{token8()}"

    def choose_output_dir(self):
        path = filedialog.askdirectory(title="Choose output folder")
        return Path(path) if path else None

    def generate_route(self):
        try:
            key = self.get_key()
            out = self.choose_output_dir()
            if not out:
                return
            file_id = self.route_id()
            conf = sign_conf(key, "route", file_id, self.route_payload())
            path = out / f"velagate-europe-route-{file_id[-8:]}.conf"
            save_conf(path, conf)
            self.last_route_id.set(file_id)
            self.traffic_route_id.set(file_id)
            self.status.set(f"Generated {path.name}")
            messagebox.showinfo("Generated", f"Route configuration created:\n{path}")
        except Exception as e:
            messagebox.showerror("Generate failed", str(e))

    def generate_traffic(self):
        try:
            key = self.get_key()
            out = self.choose_output_dir()
            if not out:
                return
            route_id = self.traffic_route_id.get().strip()
            payload = self.traffic_payload(route_id)
            file_id = self.traffic_id(self.quota_gb.get())
            conf = sign_conf(key, "traffic", file_id, payload)
            path = out / f"velagate-traffic-{self.quota_gb.get().strip()}G-{file_id[-8:]}.conf"
            save_conf(path, conf)
            self.status.set(f"Generated {path.name}")
            messagebox.showinfo("Generated", f"Traffic configuration created:\n{path}")
        except Exception as e:
            messagebox.showerror("Generate failed", str(e))

    def generate_pair(self):
        try:
            key = self.get_key()
            out = self.choose_output_dir()
            if not out:
                return
            route_id = self.route_id()
            traffic_id = self.traffic_id(self.quota_gb.get())
            route_conf = sign_conf(key, "route", route_id, self.route_payload())
            traffic_conf = sign_conf(key, "traffic", traffic_id, self.traffic_payload(route_id))
            suffix = route_id[-8:]
            route_path = out / f"velagate-europe-route-{suffix}.conf"
            traffic_path = out / f"velagate-traffic-{self.quota_gb.get().strip()}G-{traffic_id[-8:]}.conf"
            save_conf(route_path, route_conf)
            save_conf(traffic_path, traffic_conf)
            self.last_route_id.set(route_id)
            self.traffic_route_id.set(route_id)
            self.status.set("Matched pair generated")
            messagebox.showinfo("Generated", f"Matched pair created:\n\n{route_path.name}\n{traffic_path.name}\n\nRoute fileId:\n{route_id}")
        except Exception as e:
            messagebox.showerror("Generate failed", str(e))

    def load_route_for_traffic(self):
        path = filedialog.askopenfilename(title="Select route .conf", filetypes=[("VelaGate config", "*.conf"), ("All files", "*.*")])
        if not path:
            return
        try:
            route_id = read_route_file_id(path)
            self.traffic_route_id.set(route_id)
            self.last_route_id.set(route_id)
            self.status.set("Route loaded for traffic package")
        except Exception as e:
            messagebox.showerror("Load failed", str(e))


if __name__ == "__main__":
    App().mainloop()
