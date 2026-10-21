/*
 * Copyright (c) 2018 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.release;


import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Checks for new releases
 */
public final class ReleaseInfo {
    /**
     * The GitHub project this version is released from
     */
    public static final String GITHUB_PROJECT = "wakadu13/QuartusMACOpenSource";
    private static final String RELEASE_URL = "https://api.github.com/repos/" + GITHUB_PROJECT + "/releases/latest";

    private String version;
    private String url;

    /**
     * Creates a new instance
     *
     * @throws IOException IOException
     */
    ReleaseInfo() throws IOException {
        readReleaseInfo(RELEASE_URL);
    }

    private void readReleaseInfo(String releaseUrl) throws IOException {
        try (InputStream in = new URL(releaseUrl).openStream()) {
            JSONTokener tok = new JSONTokener(in);
            JSONObject obj = new JSONObject(tok);

            version = obj.get("tag_name").toString();
            url = obj.get("html_url").toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * @return the actual version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return the url of the release page
     */
    public String getUrl() {
        return url;
    }

    /**
     * Compares two version names like "v1.2.3".
     *
     * @param latest  the latest released version
     * @param running the running version
     * @return true if latest is newer than running
     */
    public static boolean isNewer(String latest, String running) {
        int[] l = parse(latest);
        int[] r = parse(running);
        if (l == null || r == null)
            return !latest.equals(running);
        for (int i = 0; i < Math.max(l.length, r.length); i++) {
            int a = i < l.length ? l[i] : 0;
            int b = i < r.length ? r[i] : 0;
            if (a != b)
                return a > b;
        }
        return false;
    }

    private static int[] parse(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V"))
            v = v.substring(1);
        String[] parts = v.split("\\.");
        int[] n = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++)
                n[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return n;
    }

}
