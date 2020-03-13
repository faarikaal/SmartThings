/*
 * Author: Vadim Bachmutsky
 * Program: Eyez-On SmartThings Switch
 * Version: 1.0
 *
 * Description:
 * Integrates SmartThings with EnvisaLink-enabled home security alarm system through
 * the use of the Eyez-On server to enable automation for arming/disarming system.
 *
 * TODO: Update switch state based on system status (feedback loop).
 *
 * Copyright 2020 Vadim Bachmutsky
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
*/

/* 
APP CONSTANTS (how ST doesn't support one of the most basic constructs of
modern programming is beyond me)
*/
def EYEZON_URI() {
	return "https://www.eyez-on.com"
}
def EYEZON_PATH() {
	return "/EZMOBILE/index.php"
}
def STATUS_READY() {
	return "Ready"
}
def STATUS_BUSY() {
	return "Busy"
}
def STATUS_EXIT_DELAY() {
	return "Exit Delay"
}
def STATUS_AWAY_ARMED() {
	return "Away Armed"
}
def STATUS_STAY_ARMED() {
	return "Stay Armed"
}
def STATUS_UNKNOWN() {
	return "Unknown"
}
def OPERATION_ARM_STAY() {
	return "armstay"
}
def OPERATION_ARM_AWAY() {
	return "armaway"
}
def OPERATION_DISARM() {
	return "disarm"
}
def ARM_MODE_STAY() {
	return "Stay"
}
def ARM_MODE_AWAY() {
	return "Away"
}

preferences {
    section("Settings"){
        input "mid", "text", title: "Account ID", required: true
        input "did", "text", title: "Device ID", required: true
        input "part", "number", title: "Partition #", required: true
        input "pin", "number", title: "Disarm PIN", required: true
        input "mode", "enum", title: "Arm Mode", options: [ARM_MODE_STAY(), ARM_MODE_AWAY()]
        input "exitDelay", "number", title: "System exit delay (in seconds)", range: "1..120", required: true
    }
}

metadata {
    definition (name: "My Eyez-On SmartThings Switch", namespace: "jsconstantelos", author: "Vadim Bachmutsky") {
        capability "Actuator"
        capability "Switch"
        capability "Sensor"
        capability "Refresh"
        capability "Polling"
        capability "Health Check"
        command "getSystemStatus"
    }

    // UI tile definitions
	tiles(scale: 2) {
		multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true){
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label:'Armed', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#00A0DC"
				attributeState "off", label:'Disarmed', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff"
				attributeState "busy", label:'Busy', icon:"st.switches.light.on", backgroundColor:"#ffa81e"
				attributeState "exiting", label:'Exiting', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#ffa81e"
			}
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:"", action:"getSystemStatus", icon:"st.secondary.refresh"
		}
		main "switch"
		details(["switch", "refresh"])
	}
}

def initialize() {
	sendEvent(name: "DeviceWatch-Enroll", value: "{\"protocol\": \"LAN\", \"scheme\":\"untracked\", \"hubHardwareId\": \"${device.hub.hardwareID}\"}", displayed: false)
    sendEvent(name: "DeviceWatch-DeviceStatus", value: "online")
    sendEvent(name: "healthStatus", value: "online")
}

void installed() {
	log.debug "installed()"
	initialize()
}

def updated() {
	log.debug "updated()"
	initialize()
}

def getSystemStatus() {
    def textData
    try {
        def params = [
            uri: EYEZON_URI(),
            path: EYEZON_PATH(),
            query: [
                "mid": settings.mid
            ],
            contentType: "text/plain"
        ] 
        httpGet(params) { resp ->
            textData = resp.data.getText()
    	}
    } catch (e) {
    	log.error 'Unable to get current system status', e
    	throw e
    }
    def systemStatus = STATUS_UNKNOWN()
    if (textData.contains(STATUS_READY())) {
    	systemStatus = STATUS_READY()
        sendEvent(name: "switch", value: "off")
    } else if (textData.contains(STATUS_BUSY())) {
    	systemStatus = STATUS_BUSY()
        sendEvent(name: "switch", value: "busy")
    } else if (textData.contains(STATUS_EXIT_DELAY())) {
    	systemStatus = STATUS_EXIT_DELAY()
        sendEvent(name: "switch", value: "exiting")
    } else if (textData.contains(STATUS_AWAY_ARMED())) {
    	systemStatus = STATUS_AWAY_ARMED()
        sendEvent(name: "switch", value: "on")
    } else if (textData.contains(STATUS_STAY_ARMED())) {
    	systemStatus = STATUS_STAY_ARMED()
        sendEvent(name: "switch", value: "on")
    }
    log.info "Determined system status to be ${systemStatus}"
    return systemStatus
}

def performOperation(operation) {
    log.info "Received request to perform operation: ${operation}"
    try {
     	def random = new Random().nextInt(99999999) + 1
        def waitTime = exitDelay
        def path = "${EYEZON_URI()}${EYEZON_PATH()}?mid=${settings.mid}&action=s&did=${settings.did}&type=15&dmdben=f&rand=${random}"
        def body = "part=${settings.part}&pin=${settings.pin}"
        if (operation == OPERATION_DISARM()) {
        	body += "&extaction=hdisarm"
            sendEvent(name: "switch", value: "busy")
            waitTime = 5  // time in seconds
        } else if (operation == OPERATION_ARM_STAY()) {
        	body += "&extaction=dohpincommand&hextaction=harmstay"
            sendEvent(name: "switch", value: "exiting")
        } else {
        	body += "&extaction=dohpincommand&hextaction=harmaway"
            sendEvent(name: "switch", value: "exiting")
        }
        //log.info "Path: ${path}"
        //log.info "Body: ${body}"
        httpPost(path, body)
        log.info "Operation ${operation} successfully submitted, now waiting for system exit/disarm delay (${waitTime} seconds for exit delay, 5 seconds for disarm delay) for confirmation..."
        runIn(waitTime, getSystemStatus, [overwrite: true])  // wait for system delay and then get system status to update the tile, and overwrite any pending schedules
    } catch (e) {
        log.error "Unable to perform operation ${operation}: POST failed.", e
    }
}

def on() {
    log.info "Received request to arm system. Mode: ${settings.mode}"
    def systemStatus = getSystemStatus()
    if (systemStatus != STATUS_READY()) {
    	log.error "Cannot arm system. Expecting status ${STATUS_READY()}. Actual status: ${systemStatus}"
        return
    }
    def operation = settings.mode == 'Away' ? OPERATION_ARM_AWAY() : OPERATION_ARM_STAY()
    performOperation(operation)
}

def off() {
    log.info "Received request to disarm system. Mode: ${settings.mode}"
    def systemStatus = getSystemStatus()
    if (![STATUS_AWAY_ARMED(), STATUS_STAY_ARMED(), STATUS_EXIT_DELAY()].contains(systemStatus)) {
    	log.error "Cannot disarm system: System is in invalid state: ${systemStatus}"
        return
    }
    if (systemStatus == STATUS_STAY_ARMED() && settings.mode == ARM_MODE_AWAY()) {
    	log.error "Cannot disarm system: Expecting status ${STATUS_AWAY_ARMED()}. Actual status: ${systemStatus}"
        return
    } else if (systemStatus == STATUS_AWAY_ARMED() && settings.mode == ARM_MODE_STAY()) {
    	log.error "Cannot disarm system: Expecting status ${STATUS_STAY_ARMED()}. Actual status: ${systemStatus}"
        return
    }
    performOperation(OPERATION_DISARM())
}

def refresh() {
	log.debug "refresh()..."
	getSystemStatus()
}

def ping() {
	log.debug "ping()..."
	refresh()
}

def poll() {
	log.debug "poll()..."
	refresh()
}