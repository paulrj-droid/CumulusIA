package com.cumulusia.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Único servicio de accesibilidad publicado por Cumulus IA.
 *
 * Android muestra una sola entrada por cada servicio declarado en el
 * manifiesto. Las acciones de control se incorporan aquí sin registrar
 * servicios adicionales.
 */
class CumulusAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // El servicio queda preparado para recibir las acciones autorizadas.
    }

    override fun onInterrupt() {
        // No hay una operación persistente que cancelar.
    }
}

