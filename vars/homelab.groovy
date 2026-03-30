/**
 * homelab — Galahad homelab control
 *
 * Usage:
 *   def out = homelab.ssh('10.201.52.171', 'docker ps')
 *   homelab.container('jellyfin', 'restart')
 *   homelab.vm(103, 'start')
 *   def status = homelab.health()
 */

import org.galahad.Config
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// ── SSH ──────────────────────────────────────────────────────────────────────

def ssh(String host, String cmd, String user = 'root') {
    def remote = [
        name:          host,
        host:          host,
        user:          user,
        allowAnyHosts: true
    ]
    withCredentials([sshUserPrivateKey(
        credentialsId:  Config.CRED_SSH,
        keyFileVariable: 'SSH_KEY_FILE'
    )]) {
        remote.identityFile = SSH_KEY_FILE
        return sshCommand(remote: remote, command: cmd)
    }
}

def sshVM105(String cmd) { return ssh(Config.VM105_HOST, cmd) }
def sshProxmox(String cmd) { return ssh(Config.PROXMOX_HOST, cmd) }

// ── Docker / Portainer ───────────────────────────────────────────────────────

def container(String name, String action) {
    // Resolve container name → ID via Portainer
    withCredentials([string(credentialsId: Config.CRED_PORTAINER, variable: 'PORTAINER_KEY')]) {
        def listResp = httpRequest(
            url: "${Config.PORTAINER_URL}/endpoints/${Config.PORTAINER_EP}/docker/containers/json?all=true",
            httpMode: 'GET',
            customHeaders: [[name: 'x-api-key', value: PORTAINER_KEY]],
            validResponseCodes: '200',
            timeout: 15
        )
        def containers = new JsonSlurper().parseText(listResp.content)
        def target = containers.find { c ->
            c.Names.any { it.replaceAll('^/', '') == name }
        }
        if (!target) {
            error "Container '${name}' not found via Portainer"
        }
        def id = target.Id
        httpRequest(
            url: "${Config.PORTAINER_URL}/endpoints/${Config.PORTAINER_EP}/docker/containers/${id}/${action}",
            httpMode: 'POST',
            customHeaders: [[name: 'x-api-key', value: PORTAINER_KEY]],
            validResponseCodes: '200:299',
            timeout: 30
        )
        echo "Container '${name}' → ${action} complete"
    }
}

def containerStatus() {
    def raw = sshVM105("docker ps -a --format '{{.Names}}|{{.State}}|{{.Status}}'")
    return raw.split('\n').collect { line ->
        def parts = line.split('\\|')
        [name: parts[0], state: parts[1], status: parts[2]]
    }
}

// ── Proxmox VMs/LXCs ────────────────────────────────────────────────────────

def vm(def vmid, String action) {
    withCredentials([string(credentialsId: Config.CRED_PROXMOX, variable: 'PVE_TOKEN')]) {
        httpRequest(
            url: "${Config.PROXMOX_API}/nodes/${Config.PROXMOX_NODE}/qemu/${vmid}/status/${action}",
            httpMode: 'POST',
            customHeaders: [[name: 'Authorization', value: "PVEAPIToken=${PVE_TOKEN}"]],
            ignoreSslErrors: true,
            validResponseCodes: '200:299',
            timeout: 30
        )
        echo "VM ${vmid} → ${action} sent"
    }
}

def lxc(def vmid, String action) {
    withCredentials([string(credentialsId: Config.CRED_PROXMOX, variable: 'PVE_TOKEN')]) {
        httpRequest(
            url: "${Config.PROXMOX_API}/nodes/${Config.PROXMOX_NODE}/lxc/${vmid}/status/${action}",
            httpMode: 'POST',
            customHeaders: [[name: 'Authorization', value: "PVEAPIToken=${PVE_TOKEN}"]],
            ignoreSslErrors: true,
            validResponseCodes: '200:299',
            timeout: 30
        )
        echo "LXC ${vmid} → ${action} sent"
    }
}

def vmList() {
    withCredentials([string(credentialsId: Config.CRED_PROXMOX, variable: 'PVE_TOKEN')]) {
        def resp = httpRequest(
            url: "${Config.PROXMOX_API}/nodes/${Config.PROXMOX_NODE}/qemu",
            httpMode: 'GET',
            customHeaders: [[name: 'Authorization', value: "PVEAPIToken=${PVE_TOKEN}"]],
            ignoreSslErrors: true,
            validResponseCodes: '200',
            timeout: 15
        )
        return new JsonSlurper().parseText(resp.content).data
    }
}

// ── Health summary ───────────────────────────────────────────────────────────

def health() {
    def raw = sshVM105(
        'docker ps -a --format \'{{.Names}}|{{.State}}\' | ' +
        'python3 -c "import sys,json; rows=[l.strip().split(\'|\') for l in sys.stdin if \'|\' in l]; ' +
        'down=[r[0] for r in rows if len(r)>1 and r[1]!=\'running\']; ' +
        'running=[r[0] for r in rows if len(r)>1 and r[1]==\'running\']; ' +
        'print(json.dumps({\'total\':len(rows),\'running\':len(running),\'down\':len(down),\'down_names\':down}))"'
    )
    // raw may contain ssh banner lines — find the JSON line
    def jsonLine = raw.split('\n').find { it.trim().startsWith('{') }
    return new JsonSlurper().parseText(jsonLine ?: '{"total":0,"running":0,"down":0,"down_names":[]}')
}
