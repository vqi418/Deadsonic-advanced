/*
  This file is part of Airsonic.

  Airsonic is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Airsonic is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

  Copyright 2024 (C) Y.Tory
  Copyright 2017 (C) Airsonic Authors
  Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
*/
package org.airsonic.player.service.upnp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public enum ProcessorType {

    ROOT("0"),
    PLAYLIST("playlist"),
    FOLDER("folder"),
    ALBUM("album"),
    ARTIST("artist"),
    ARTISTALBUM("artistalbum"),
    GENRE("genre"),
    RECENT("recent"),
    MEDIAFILE("mediafile"),
    UNKNOWN("unknown");

    private String keyType;

    private ProcessorType(String keyType) {
        this.keyType = keyType;
    }

    public String getKeyType() {
        return this.keyType;
    }

    @Nonnull
    public static ProcessorType toEnum(@Nullable String keyType) {
        for (ProcessorType pt: values()) {
            if (pt.getKeyType().equalsIgnoreCase(keyType)) return pt;
        }
        return UNKNOWN;
    }

}
