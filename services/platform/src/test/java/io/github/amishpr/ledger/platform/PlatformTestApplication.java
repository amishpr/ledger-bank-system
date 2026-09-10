package io.github.amishpr.ledger.platform;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal application the platform's own tests boot against. It deliberately
 * does no component scanning: this package holds the auto-configurations
 * themselves, and scanning it would register their nested configuration
 * classes without their conditions. Tests import what they need instead.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PlatformTestApplication {}
