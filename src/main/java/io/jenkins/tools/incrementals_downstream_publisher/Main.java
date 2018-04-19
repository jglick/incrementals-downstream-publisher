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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class Main {

    public static void main(String[] argv) throws Exception {
        String upstreamURL = System.getenv("UPSTREAM_URL");
        if (upstreamURL == null) {
            throw new IllegalStateException("Specify $UPSTREAM_URL");
        }
        if (!upstreamURL.endsWith("/")) {
            throw new IllegalStateException("$UPSTREAM_URL must end in a /");
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
        String deployURL = System.getenv("DEPLOY_URL");
        if (deployURL == null) {
            throw new IllegalStateException("Specify $DEPLOY_URL");
        }
        if (!deployURL.endsWith("/")) {
            throw new IllegalStateException("$DEPLOY_URL must end in a /");
        }
        String deployAuth = System.getenv("DEPLOY_AUTH");
        if (deployAuth == null) {
            throw new IllegalStateException("Specify $DEPLOY_AUTH");
        }
        String rpuIndexURL = System.getenv("RPU_INDEX");
        if (rpuIndexURL == null) {
            throw new IllegalStateException("Specify $RPU_INDEX");
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
        data = JsonPath.parse(new URL(rpuIndexURL));
        List<String> allowedPaths = data.read("$['" + ownerRepo + "']");
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
        String pom = null;
        try (ZipFile zf = new ZipFile(download)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                System.out.println("Entry: " + entry);
                if (!allowedPaths.stream().anyMatch(path -> entry.startsWith(path + "/"))) {
                    throw new IllegalStateException("Attempt to deploy " + entry + " which is not inside " + allowedPaths + " specified for " + ownerRepo + " by " + rpuIndexURL);
                }
                if (pom == null && entry.endsWith(".pom")) {
                    pom = entry;
                }
            }
        }
        System.out.println("All entries fall within permitted paths: " + allowedPaths.stream().collect(Collectors.joining(" ")));
        if (pom == null) {
            System.out.println("No permitted artifacts including a POM recorded (perhaps due to a PR merge build not up to date with the target branch); skipping deployment.");
            Files.delete(download.toPath());
            return;
        }
        conn = (HttpURLConnection) new URL(deployURL + pom).openConnection();
        if (conn.getResponseCode() == 200) {
            System.out.println(deployURL + pom + " already exists; skipping redeployment.");
            return;
        }
        conn = (HttpURLConnection) new URL(deployURL + "archive.zip").openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("X-Explode-Archive", "true");
        conn.setRequestProperty("X-Explode-Archive-Atomic", "true");
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(deployAuth.getBytes(StandardCharsets.UTF_8)));
        conn.setRequestProperty("Content-Length", Long.toString(download.length()));
        conn.setRequestMethod("PUT");
        try (OutputStream os = conn.getOutputStream()) {
            FileUtils.copyFile(download, os);
        }
        int status = conn.getResponseCode();
        if (status != 200) {
            InputStream errs = conn.getErrorStream();
            throw new IllegalStateException("Deployment failed with code " + status + (errs != null ? ": " + IOUtils.toString(errs, conn.getContentEncoding()).trim() : ""));
        }
        conn = (HttpURLConnection) new URL(deployURL + pom).openConnection();
        if (conn.getResponseCode() == 200) {
            System.out.println("Deployment successful.");
        } else {
            throw new IllegalStateException("Deployment claimed successful but the artifacts do not really seem to be there.");
        }
    }

}
