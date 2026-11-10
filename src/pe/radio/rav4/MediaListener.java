package pe.radio.rav4;

import android.service.notification.NotificationListenerService;

/**
 * Servicio vacío de "acceso a notificaciones". Con él activado, Android deja leer las sesiones de
 * música activas (MediaSessionManager.getActiveSessions) para mostrar y controlar lo que suena.
 */
public class MediaListener extends NotificationListenerService {
}
