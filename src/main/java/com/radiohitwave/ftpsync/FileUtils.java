/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.radiohitwave.ftpsync;

/**
 *
 * @author jan
 */
import com.radiohitwave.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;

class FileUtils {

    private FileUtils() {

    }

    static public boolean deleteDirectory(File path) {
        if (path.exists()) {
            File[] files = path.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    deleteDirectory(files[i]);
                } else {
                    files[i].delete();
                }
            }
        }
        return (path.delete());
    }

    public static String getFileHash(final String filePath) {
        final File file = new File(filePath);

        try {

            final MessageDigest messageDigest = MessageDigest.getInstance("SHA1");

            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                final byte[] buffer = new byte[1024];
                for (int read = 0; (read = is.read(buffer)) != -1;) {
                    messageDigest.update(buffer, 0, read);
                }
            }

            // Convert the byte to hex format
            try (Formatter formatter = new Formatter()) {
                for (final byte b : messageDigest.digest()) {
                    formatter.format("%02x", b);
                }
                return formatter.toString();
            }

        } catch (NoSuchAlgorithmException | IOException e) {
            Log.error(e.toString());
            Log.remoteError(e);
        }

        return null;
    }

    public static String getExecutableFilePath() {
        return new java.io.File(FileUtils.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath())
                .getAbsolutePath();
    }

    public static boolean isDebugMode() {
        URL path = FileUtils.class.getResource("FileUtils.class");
        return !path.toString().startsWith("jar:");
    }
}
