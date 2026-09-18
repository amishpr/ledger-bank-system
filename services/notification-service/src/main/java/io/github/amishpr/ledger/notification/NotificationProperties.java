package io.github.amishpr.ledger.notification;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param allowedOrigins origin patterns allowed to open the socket, checked on
 *     the handshake. The dashboard's address in each environment.
 */
@ConfigurationProperties("notification")
record NotificationProperties(@DefaultValue("http://localhost:*") List<String> allowedOrigins) {}
