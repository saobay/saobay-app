package com.saobay.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Goi API web truong (Worker SAOBAY) — cung cac duong ma trang web dang dung. */
public class Api {
    public static final String BASE = "https://www.saobayhighschool.workers.dev";

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    public static String getText(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "SaoBayApp");
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is != null ? readAll(is) : "";
            if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static JSONObject postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "SaoBayApp");
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is != null ? readAll(is) : "";
            try {
                return new JSONObject(resp);
            } catch (Exception e) {
                throw new Exception("HTTP " + code + ": " + resp);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
