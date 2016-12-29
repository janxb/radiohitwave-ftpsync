/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.radiohitwave.ftpsync;

import com.radiohitwave.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 *
 * @author broddaja
 */
public class FTPSync {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            if (!init(args)) {
                return;
            }
            startup(args);

        } catch (Exception e) {
            Log.error("Catched unknown Exception!");
            JOptionPane.showMessageDialog(null,
                    "Leider ist ein unbekannter Fehler aufgetreten. \n Ich kümmere mich darum..",
                    "Unbekannter Fehler aufgetreten",
                    JOptionPane.ERROR_MESSAGE);
            Log.remoteError(e);
        }
    }

    private static boolean lockInstance() {
        final String lockFile;
        try {
            lockFile = System.getProperty("java.io.tmpdir") + "/FTPSync.lock";
            final File file = new File(lockFile);
            final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
            final FileLock fileLock = randomAccessFile.getChannel().tryLock();
            if (fileLock != null) {
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    public void run() {
                        try {
                            fileLock.release();
                            randomAccessFile.close();
                            file.delete();
                        } catch (Exception e) {
                            Log.error("Unable to remove lock file: " + lockFile);
                            Log.remoteError(e);
                        }
                    }
                });
                return true;
            }
        } catch (Exception e) {
            Log.error("Unable to create lock file");
            Log.remoteError(e);
        }
        JOptionPane.showMessageDialog(null, "FTPSync konnte nicht gestartet werden.\nLäuft vielleicht im Hintergrund noch eine Instanz?");
        return false;
    }

    private static boolean init(String[] args) {
        //System.setProperty("https.proxyHost", "de-proxy.int.actebis.com");
        //System.setProperty("https.proxyPort", "8080");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException ex) {
            Log.remoteError(ex);
        }

        if (!FTPSync.lockInstance()) {
            return false;
        } else {
            return true;
        }
    }

    private static void startup(String[] args) {
        try {
            API api = API.getInstance();
            api.DeleteTempFiles();

            Log.remoteInfo("Starting Build: " + api.version);

            if (api.settings.get("username", null) != null
                    && api.settings.get("password", null) != null) {
                Log.info("Using saved Login-Data");
                if (api.LoginEncrypted(
                        api.settings.get("username", null),
                        api.settings.get("password", null))) {
                    Log.info("User authenticated, opening MainWindow");
                    MainWindow.main(args);
                } else {
                    Log.warning("Saved Login-Data was incorrect, deleting local Files");
                    api.DeleteLocalData(api.settings.get("localpath", null));
                    LoginWindow.main(args);
                }
            } else {
                Log.info("No saved Login-Data found, opening LoginWindow");
                LoginWindow.main(args);
            }
        } catch (Exception e) {
            Log.remoteError(e);
        }
    }

}
