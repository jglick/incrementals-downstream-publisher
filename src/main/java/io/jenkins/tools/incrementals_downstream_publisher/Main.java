/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.tools.incrementals_downstream_publisher;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FileUtils;

public class Main {

    public static void main(String[] argv) throws Exception {
        String upstreamURL = System.getenv("UPSTREAM_URL");
        if (upstreamURL == null) {
            throw new IllegalStateException("Specify $UPSTREAM_URL");
        }
        String allowedUpstreamFolders = System.getenv("ALLOWED_UPSTREAM_FOLDERS");
        if (allowedUpstreamFolders == null) {
            throw new IllegalStateException("Specify $ALLOWED_UPSTREAM_FOLDERS");
        }
        if (!Stream.of(allowedUpstreamFolders.split(" ")).anyMatch(allowed -> upstreamURL.matches("\\Q" + allowed + "\\E(job/[^/]+/)+[0-9]+/"))) {
            throw new IllegalStateException(upstreamURL + " not inside " + allowedUpstreamFolders);
        }
        String downloadN = System.getenv("DOWNLOAD");
        if (downloadN == null) {
            throw new IllegalStateException("Specify $DOWNLOAD");
        }
        URL upstreamMetadata = new URL(upstreamURL + "api/json?tree=actions[revision[hash,pullHash],remoteUrls]");
        System.out.println("Parsing: " + upstreamMetadata);
        DocumentContext data = JsonPath.parse(upstreamMetadata);
        List<Map<String, String>> hashes = data.read("$.actions[*].revision.['hash', 'pullHash']");
        String hash = hashes.get(0).entrySet().iterator().next().getValue().substring(0, 12);
        System.out.println("Commit hash: " + hash);
        List<List<String>> remoteUrls = data.read("$.actions[*].remoteUrls");
        if (remoteUrls.size() != 1 || remoteUrls.get(0).size() != 1) {
            throw new IllegalStateException("Could not read remoteUrls: " + remoteUrls);
        }
        String remoteUrl = remoteUrls.get(0).get(0);
        Matcher m = Pattern.compile("https://github[.]com/([^/]+/[^/]+?)([.]git)?").matcher(remoteUrl);
        if (!m.matches()) {
            throw new IllegalStateException("Did not recognize: " + remoteUrl);
        }
        String ownerRepo = m.group(1);
        System.out.println("GitHub: " + ownerRepo);
        String ghAuth = System.getenv("GITHUB_AUTH");
        URL commit = new URL("https://" + (ghAuth != null ? ghAuth + "@" : "") + "api.github.com/repos/" + ownerRepo + "/commits/" + hash);
        HttpURLConnection conn = (HttpURLConnection) commit.openConnection();
        if (conn.getResponseCode() == 200) {
            data = JsonPath.parse(conn.getInputStream());
            boolean verified = data.read("$.commit.verification.verified");
            String committer = data.read("$.committer.login");
            // We may decide to decline to publish unsigned commits. For now this is just informational:
            System.out.println("Commit hash seems to be unique. " + (verified ? "Signed & verified" : "Unverified") + " commit by " + committer + ".");
        } else {
            throw new IllegalStateException("Commit hash does not seem to exist, or prefix is not unique.");
        }
        conn.disconnect();
        URL zip = new URL(upstreamURL + "artifact/**/*-rc*." + hash + "/*-rc*." + hash + "*/*zip*/archive.zip");
        File download = new File(downloadN);
        FileUtils.copyURLToFile(zip, download);
        System.out.println("Artifacts: " + download);
        try (ZipFile zf = new ZipFile(download)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                System.out.println("Entry: " + entries.nextElement().getName());
            }
        }
    }

}
