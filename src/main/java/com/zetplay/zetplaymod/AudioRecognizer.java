package com.zetplay.zetplaymod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class AudioRecognizer {

    private static final HttpClient client = HttpClient.newHttpClient();

    /**
     * Converts raw PCM bytes into a valid WAV file byte array.
     */
    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int totalDataLen = pcmData.length + 36;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; 
        header[20] = 1; header[21] = 0; 
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * bitsPerSample / 8); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        byte[] wav = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcmData, 0, wav, header.length, pcmData.length);
        return wav;
    }

    /**
     * Sends WAV bytes to ACRCloud API for recognition using settings from ZetPlayConfig.
     */
    public static String recognize(byte[] wavBytes) {
        try {
            ZetPlayConfig cfg = ZetPlayConfig.get();
            String host = cfg.acrHost;
            String accessKey = cfg.acrAccessKey;
            String accessSecret = cfg.acrAccessSecret;

            if (accessKey == null || accessKey.isBlank() || accessSecret == null || accessSecret.isBlank()) {
                ZetPlayMod.LOGGER.warn("[ZetPlay/ACR] ACRCloud credentials missing in config/zetplay.json");
                return null;
            }

            String httpMethod = "POST";
            String httpUri = "/v1/identify";
            String dataType = "audio";
            String signatureVersion = "1";
            String timestamp = String.valueOf(Instant.now().getEpochSecond());

            // 1. Generate HMAC-SHA1 Signature
            String stringToSign = httpMethod + "\n" + httpUri + "\n" + accessKey + "\n" + dataType + "\n" + signatureVersion + "\n" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secretKeySpec = new SecretKeySpec(accessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(secretKeySpec);
            byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signatureBytes);

            // 2. Build Multipart Form Data
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().substring(0, 8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            writeFormField(body, boundary, "access_key", accessKey);
            writeFormField(body, boundary, "data_type", dataType);
            writeFormField(body, boundary, "signature_version", signatureVersion);
            writeFormField(body, boundary, "signature", signature);
            writeFormField(body, boundary, "sample_bytes", String.valueOf(wavBytes.length));
            writeFormField(body, boundary, "timestamp", timestamp);

            // Add audio file
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write("Content-Disposition: form-data; name=\"sample\"; filename=\"sample.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(wavBytes);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            // 3. Send Request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + httpUri))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // 4. Parse JSON Response
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("status") && json.getAsJsonObject("status").get("code").getAsInt() == 0) {
                JsonObject musicMatch = json.getAsJsonObject("metadata")
                                            .getAsJsonArray("music")
                                            .get(0).getAsJsonObject();
                String artist = musicMatch.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
                String title = musicMatch.get("title").getAsString();
                return artist + " - " + title;
            } else {
                 ZetPlayMod.LOGGER.info("[ZetPlay/ACR] No match found or API error. Response: " + response.body());
            }

        } catch (Exception e) {
            ZetPlayMod.LOGGER.error("[ZetPlay/ACR] Recognition error", e);
        }
        return null;
    }

    private static void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}