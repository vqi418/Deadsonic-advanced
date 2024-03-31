package org.airsonic.player.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "airsonic.cue")
public class AirsonicCueConfig {

    // properties
    private boolean enabled;
    private boolean hideIndexedFiles;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHideIndexedFiles() {
        return enabled && hideIndexedFiles;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setHideIndexedFiles(boolean hideIndexedFiles) {
        this.hideIndexedFiles = hideIndexedFiles;
    }
}
