#!groovy
import java.text.DateFormat
import java.text.SimpleDateFormat
import groovy.transform.Field

//global vars
@Field LOG_LEVEL = env.LOG_LEVEL
//set values here to determine hierarchy
@Field DEBUG_LEVEL = 4
@Field INFO_LEVEL = 3
@Field WARN_LEVEL = 2
@Field ERROR_LEVEL = 1

def castLogLevel(String logLevel) {    
    switch (logLevel) {
        case 'DEBUG' : 
            castLogLevel = DEBUG_LEVEL
            break
        case 'WARN' : 
            castLogLevel = WARN_LEVEL
            break
        case 'ERROR' : 
            castLogLevel = ERROR_LEVEL
            break
        default:
            castLogLevel = INFO_LEVEL
    }

    return castLogLevel
}

def debug(String message) {
    def logLevel = castLogLevel(LOG_LEVEL)
    if (logLevel >= DEBUG_LEVEL) {        
        println("DEBUG" + " - " + message)        
    }
}

def info(String message) {
    def logLevel = castLogLevel(LOG_LEVEL)
    if (logLevel >= INFO_LEVEL) {        
        println("INFO" + " - " + message)  
    }       
}

def warn(String message) {
    def logLevel = castLogLevel(LOG_LEVEL)
    if (logLevel >= WARN_LEVEL) {
        println("WARN" + " - " + message)    
    }
}

def error(String message) {       
    println("ERROR" + " - " + message)
}

