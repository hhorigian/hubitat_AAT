/*
 * Hubitat Driver (Parent)
 * AAT Multiroom - conexão curta (open -> send -> parse -> close)
 * 
 *
 * Recursos:
 * - 2 zonas de volume com child dimmers
 * - power on/off no device pai
 * - 4 child switches de source/input
 *   - Z1 Input 1
 *   - Z1 Input 6
 *   - Z2 Input 1
 *   - Z2 Input 6
 *
 * Lógica:
 * - Ao ligar um input de uma zona, os demais inputs da mesma zona desligam
 * - Ao tentar desligar manualmente o input ativo, ele é religado visualmente
 *   para manter a ideia de "fonte selecionada"
 *  V. 1.0  -  VH - Melhorada a Conexão. Adicionado o retorno HEX para String para pegar o feedback. Alterado para conseguir usar o Max Volume


 */

metadata {
    definition(name: "AAT Multiroom 2 Zonas", namespace: "Cesco", author: "Felipe") {
        capability "Initialize"
        capability "Switch"
        capability "Refresh"

        command "createChildDevices"
        command "deleteChildDevices"
        command "connectSocket"

        command "powerOn"
        command "powerOff"

        command "setZoneInput", [
            [name: "Zona", type: "NUMBER", description: "Número da zona"],
            [name: "Input", type: "NUMBER", description: "Número do input"]
        ]

        command "setInputZ1_1"
        command "setInputZ1_6"
        command "setInputZ2_1"
        command "setInputZ2_6"

        attribute "lastResponse", "string"
        attribute "zone1Input", "number"
        attribute "zone2Input", "number"
    }
}

preferences {
    input name: "ip", type: "text", title: "IP do Multiroom", required: true
    input name: "port", type: "number", title: "Porta", defaultValue: 5000
    input name: "controllerId", type: "text", title: "ID Controlador", defaultValue: "t001"

    input name: "maxAatVolume", type: "number", title: "Volume máximo AAT (padrão 87)", defaultValue: 87
    input name: "debounceMs", type: "number", title: "Debounce slider (ms)", defaultValue: 250
    input name: "logEnable", type: "bool", title: "Habilitar logs de debug", defaultValue: true
}

// ------------------------------------
// Lifecycle
// ------------------------------------

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    logDebug "Initialize chamado"
    createChildDevices()

    if (device.currentValue("switch") == null) {
        sendEvent(name: "switch", value: "off")
    }

    syncAllInputChildren()
    state.connected = false
    runIn(1, "connectSocket")
}

def connectSocket() {
    if (state.connected) {
        safeClose()
        pauseExecution(300)
    }
    try {
        log.info "Conectando a ${ip}:${port ?: 5000}"
        interfaces.rawSocket.connect(ip, (port ?: 5000) as Integer, byteInterface: false)
        state.connected = true
        log.info "Socket conectado"
    } catch (e) {
        log.error "Falha ao conectar: ${e.message}"
        state.connected = false
    }
}

def socketStatus(String status) {
    logDebug "socketStatus: ${status}"
    if (status.toLowerCase().contains("error") || status.toLowerCase().contains("disconnect") || status.toLowerCase().contains("closed")) {
        state.connected = false
        log.warn "Conexão perdida (${status}) — reconectando em 15s"
        runIn(15, "connectSocket")
    }
}

// ------------------------------------
// Child management
// ------------------------------------

def createChildDevices() {
    createChildDimmer("${device.id}-VOL-Z1", "${device.displayName} - Volume Zona 1")
    createChildDimmer("${device.id}-VOL-Z2", "${device.displayName} - Volume Zona 2")

    createChildSwitch("${device.id}-SRC-Z1-I1", "${device.displayName} - Z1 Input 1")
    createChildSwitch("${device.id}-SRC-Z1-I6", "${device.displayName} - Z1 Input 6")
    createChildSwitch("${device.id}-SRC-Z2-I1", "${device.displayName} - Z2 Input 1")
    createChildSwitch("${device.id}-SRC-Z2-I6", "${device.displayName} - Z2 Input 6")

    syncAllInputChildren()
}

private void createChildDimmer(String dni, String label) {
    if (!getChildDevice(dni)) {
        addChildDevice(
            "hubitat",
            "Generic Component Dimmer",
            dni,
            [name: label, label: label, isComponent: true]
        )
        logDebug "Child dimmer criado: ${label} (${dni})"
    }
}

private void createChildSwitch(String dni, String label) {
    if (!getChildDevice(dni)) {
        addChildDevice(
            "hubitat",
            "Generic Component Switch",
            dni,
            [name: label, label: label, isComponent: true]
        )
        logDebug "Child switch criado: ${label} (${dni})"
    }
}

def deleteChildDevices() {
    getChildDevices()?.each { cd ->
        try {
            deleteChildDevice(cd.deviceNetworkId)
            logDebug "Child removido: ${cd.displayName} (${cd.deviceNetworkId})"
        } catch (e) {
            log.warn "Não consegui remover ${cd.deviceNetworkId}: ${e}"
        }
    }
}

// ------------------------------------
// Parent power
// ------------------------------------

def on() {
    powerOn()
}

def off() {
    powerOff()
}

def powerOn() {
    enviar(frame("PWRON"))
    sendEvent(name: "switch", value: "on")
}

def powerOff() {
    enviar(frame("PWROFF"))
    sendEvent(name: "switch", value: "off")
}

// ------------------------------------
// Input commands
// ------------------------------------

def setInputZ1_1() { setZoneInput(1, 1) }
def setInputZ1_6() { setZoneInput(1, 6) }
def setInputZ2_1() { setZoneInput(2, 1) }
def setInputZ2_6() { setZoneInput(2, 6) }

def setZoneInput(zona, inputNum) {
    Integer z = clampInt(zona, 1, 99)
    Integer i = clampInt(inputNum, 1, 99)

    String cmd = "[${controllerId ?: 't001'} INPSET ${z} ${i}]"
    enviar(cmd)

    logDebug "Selecionando input ${i} na zona ${z}"

    state["currentInputZ${z}"] = i
    sendEvent(name: "zone${z}Input", value: i)
    syncZoneInputChildren(z)
}

// ------------------------------------
// Component callbacks
// ------------------------------------

def componentSetLevel(cd, level, rate = null) {
    level = clampInt(level, 0, 100)

    def zona = zoneFromVolumeDni(cd.deviceNetworkId)
    if (!zona) return

    int delayMs = (debounceMs ?: 250) as Integer

    if (cd.currentValue("switch") == "off") {
        enviar("[${controllerId ?: 't001'} ZSTDBYOFF ${zona}]")
        cd.sendEvent(name: "switch", value: "on")
        delayMs = 1500  // aguarda amp sair do standby antes de enviar volume
    }

    state["pendingLevelZ${zona}"] = level
    runInMillis(delayMs, "flushZone${zona}")

    cd.sendEvent(name: "level", value: level)
}

def componentOn(cd) {
    String dni = cd.deviceNetworkId ?: ""

    // Child dimmer ligado = tira zona do standby
    Integer volumeZone = zoneFromVolumeDni(dni)
    if (volumeZone) {
        enviar("[${controllerId ?: 't001'} ZSTDBYOFF ${volumeZone}]")
        cd.sendEvent(name: "switch", value: "on")
        return
    }

    // Child source switch
    def src = sourceFromDni(dni)
    if (src) {
        setZoneInput(src.zone as Integer, src.input as Integer)
        getChildDevice(dni)?.sendEvent(name: "switch", value: "on")
        return
    }
}

def componentOff(cd) {
    String dni = cd.deviceNetworkId ?: ""

    Integer volumeZone = zoneFromVolumeDni(dni)
    if (volumeZone) {
        enviar("[${controllerId ?: 't001'} ZSTDBYON ${volumeZone}]")
        cd.sendEvent(name: "switch", value: "off")
        return
    }

    def src = sourceFromDni(dni)
    if (src) {
        Integer current = (state["currentInputZ${src.zone}"] ?: 0) as Integer
        if (current == (src.input as Integer)) {
            cd.sendEvent(name: "switch", value: "on")
        } else {
            cd.sendEvent(name: "switch", value: "off")
        }
    }
}
def flushZone1() { flushZone(1) }
def flushZone2() { flushZone(2) }

private void flushZone(Integer zona) {
    def level = state["pendingLevelZ${zona}"]
    if (level == null) return

    int aatMax = (maxAatVolume ?: 87) as Integer
    int volConvertido = Math.min((level as Integer), aatMax)

    enviar("[${controllerId ?: 't001'} VOLSET ${zona} ${volConvertido}]")
}

// ------------------------------------
// TCP
// ------------------------------------

private String frame(String cmd) {
    return "[${controllerId ?: 't001'} ${cmd}]"
}

private void logDebug(String msg) { if (logEnable) log.debug msg }

private void enviar(String comando) {
    logDebug "Enviando: ${comando}"
    if (!state.connected) {
        log.warn "Socket não conectado — tentando reconectar antes de enviar"
        connectSocket()
        pauseExecution(800)
    }
    try {
        interfaces.rawSocket.sendMessage(comando + "\r")
    } catch (e) {
        log.error "Erro envio: ${e.message}"
        state.connected = false
        runIn(5, "connectSocket")
    }
}

def refresh() {
    enviar(frame("GETALL"))
}

def parse(String message) {
    // Device uses \r termination; Hubitat returns raw bytes as hex string — decode first
    String decoded
    try {
        decoded = new String(message.decodeHex())
    } catch (e) {
        decoded = message
    }
    logDebug "Recebido: ${decoded}"
    sendEvent(name: "lastResponse", value: decoded)

    if (!decoded.contains("GETALL")) return

    // GETALL response tokens (0-based after stripping brackets):
    // [0]=R001 [1]=GETALL [2]=MODEL [3]=VER [4]=POWER [5]=TCPPORT [6]=TCPTIMEOUT
    // [7]=INPUT1 [8]=VOL1 [9]=MUTE1 [10]=BASS1 [11]=TREBLE1 [12]=BAL1 [13]=PREAMP1
    // [14]=INPUT2 [15]=VOL2 [16]=MUTE2 ...
    try {
        String clean = decoded.replaceAll(/[\[\]]/, "").trim()
        def tokens = clean.split(/\s+/)
        if (tokens.size() >= 16) {
            int input1 = tokens[7].toInteger()
            int vol1   = tokens[8].toInteger()
            int input2 = tokens[14].toInteger()
            int vol2   = tokens[15].toInteger()

            state["currentInputZ1"] = input1
            state["currentInputZ2"] = input2
            sendEvent(name: "zone1Input", value: input1)
            sendEvent(name: "zone2Input", value: input2)
            syncAllInputChildren()

            def z1Dimmer = getChildDevice("${device.id}-VOL-Z1")
            def z2Dimmer = getChildDevice("${device.id}-VOL-Z2")
            if (z1Dimmer) z1Dimmer.sendEvent(name: "level", value: deviceVolToPercent(vol1))
            if (z2Dimmer) z2Dimmer.sendEvent(name: "level", value: deviceVolToPercent(vol2))

            logDebug "GETALL parsed — Z1: input=${input1} vol=${vol1}  Z2: input=${input2} vol=${vol2}"
        }
    } catch (e) {
        log.warn "parse: erro ao interpretar GETALL: ${e}"
    }
}

private int deviceVolToPercent(int aatVol) {
    return Math.min(aatVol, 100)
}

private void safeClose() {
    try {
        interfaces.rawSocket.close()
    } catch (ignored) {}
}

// ------------------------------------
// Sync visual dos switches de input
// ------------------------------------

private void syncAllInputChildren() {
    syncZoneInputChildren(1)
    syncZoneInputChildren(2)
}

private void syncZoneInputChildren(Integer zona) {
    Integer currentInput = (state["currentInputZ${zona}"] ?: 0) as Integer

    def map = [
        1: ["${device.id}-SRC-Z1-I1", "${device.id}-SRC-Z1-I6"],
        2: ["${device.id}-SRC-Z2-I1", "${device.id}-SRC-Z2-I6"]
    ]

    map[zona]?.each { dni ->
        def child = getChildDevice(dni)
        if (!child) return

        def src = sourceFromDni(dni)
        if (!src) return

        String val = ((src.input as Integer) == currentInput) ? "on" : "off"
        child.sendEvent(name: "switch", value: val)
    }
}

// ------------------------------------
// Helpers
// ------------------------------------

private Integer clampInt(val, Integer min, Integer max) {
    Integer n
    try {
        n = (val as BigDecimal).intValue()
    } catch (e) {
        n = min
    }
    if (n < min) return min
    if (n > max) return max
    return n
}

private Integer zoneFromVolumeDni(String dni) {
    if (!dni) return null
    if (dni.endsWith("-Z1") || dni.endsWith("Z1") || dni.endsWith("-VOL-Z1")) return 1
    if (dni.endsWith("-Z2") || dni.endsWith("Z2") || dni.endsWith("-VOL-Z2")) return 2
    return null
}

private Map sourceFromDni(String dni) {
    if (!dni) return null

    if (dni.endsWith("-SRC-Z1-I1")) return [zone: 1, input: 1]
    if (dni.endsWith("-SRC-Z1-I6")) return [zone: 1, input: 6]
    if (dni.endsWith("-SRC-Z2-I1")) return [zone: 2, input: 1]
    if (dni.endsWith("-SRC-Z2-I6")) return [zone: 2, input: 6]

    return null
}
