/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.persistence.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ConnectionUrlSanitizerTest : StringSpec({

    "masks userinfo password in PostgreSQL URL preserving user and host" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://user:secret@host:5432/db") shouldBe
            "jdbc:postgresql://user:****@host:5432/db"
    }

    "masks userinfo password when username is empty" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://:secret@host:5432/db") shouldBe
            "jdbc:postgresql://:****@host:5432/db"
    }

    "masks query-string password in PostgreSQL URL preserving other params" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://host:5432/db?user=alice&password=secret&ssl=true") shouldBe
            "jdbc:postgresql://host:5432/db?user=alice&password=****&ssl=true"
    }

    "masks userinfo and query password in MySQL URL together" {
        ConnectionUrlSanitizer.sanitize("jdbc:mysql://root:hunter2@host:3306/db?useSSL=false&password=other") shouldBe
            "jdbc:mysql://root:****@host:3306/db?useSSL=false&password=****"
    }

    "masks capitalised query password key in MariaDB URL case-insensitively" {
        ConnectionUrlSanitizer.sanitize("jdbc:mariadb://host/db?PASSWORD=secret&user=root") shouldBe
            "jdbc:mariadb://host/db?PASSWORD=****&user=root"
    }

    "preserves SQLite plain path URL verbatim when no credentials present" {
        ConnectionUrlSanitizer.sanitize("jdbc:sqlite:/var/lib/app.db") shouldBe
            "jdbc:sqlite:/var/lib/app.db"
    }

    "masks query password in SQLite URI form preserving file path" {
        ConnectionUrlSanitizer.sanitize("jdbc:sqlite:file:/var/lib/app.db?password=secret") shouldBe
            "jdbc:sqlite:file:/var/lib/app.db?password=****"
    }

    "masks semicolon-delimited password key in H2 URL case-insensitively" {
        ConnectionUrlSanitizer.sanitize("jdbc:h2:mem:test;user=sa;password=secret;DB_CLOSE_DELAY=-1") shouldBe
            "jdbc:h2:mem:test;user=sa;password=****;DB_CLOSE_DELAY=-1"
    }

    "masks semicolon-delimited PASSWORD in H2 tcp URL" {
        ConnectionUrlSanitizer.sanitize("jdbc:h2:tcp://host:9092/~/test;PASSWORD=hunter2") shouldBe
            "jdbc:h2:tcp://host:9092/~/test;PASSWORD=****"
    }

    "masks URL-encoded userinfo password replacing the whole encoded value" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://user:s%40cret%21@host:5432/db") shouldBe
            "jdbc:postgresql://user:****@host:5432/db"
    }

    "masks URL-encoded query password value replacing the whole encoded value" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://host/db?password=s%40cret%21&ssl=true") shouldBe
            "jdbc:postgresql://host/db?password=****&ssl=true"
    }

    "preserves URL verbatim when password is absent" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://host:5432/db?user=alice") shouldBe
            "jdbc:postgresql://host:5432/db?user=alice"
    }

    "returns malformed input verbatim without throwing" {
        ConnectionUrlSanitizer.sanitize("not-a-url") shouldBe "not-a-url"
    }

    "returns empty input verbatim without throwing" {
        ConnectionUrlSanitizer.sanitize("") shouldBe ""
    }

    "masks multiple query password occurrences when both present" {
        ConnectionUrlSanitizer.sanitize("jdbc:postgresql://host/db?password=first&user=u&password=second") shouldBe
            "jdbc:postgresql://host/db?password=****&user=u&password=****"
    }
})