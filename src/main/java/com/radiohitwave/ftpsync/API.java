/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.radiohitwave.ftpsync;

import com.radiohitwave.Log;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

/**
 *
 * @author broddaja
 */
public final class API {

    private final String apiDomain = "https://sync.radiohitwave.com/";
    private final String apiRemoteFile = "api.php";
    private final String apiURL = this.apiDomain + this.apiRemoteFile;
    private final String apiToken = "CodePortal~FTPsync";
    private final String ftpURL = "ftp.radiohitwave.com:21";
    private final String updateRemoteFile = "app.php";
    private final String updateLocalFile = "FTPSync.jar";
    private final String apiListDivider = "~";
    private final String tempFilePath = System.getProperty("java.io.tmpdir") + "/FTPSync/";
    private final long millisecondsBetweenOnlineChecks = 30 * 1000;
    private final int nonExistingFilesThreesholdBeforeAlert = 10;
    private final int minimumApiResponseCharsBeforeAlert = 0;

    public static final String version;

    static {
        if (FileUtils.isDebugMode()) {
            version = "DEBUG";
        } else {
            version = FileUtils.getFileHash(FileUtils.getExecutableFilePath());
        }
    }

    private String loginName;
    private String loginPassword;
    private Boolean _isAuthenticated = false;
    private String ftpLoginName;
    private String ftpLoginPassword;
    private int currentlyDownloadedFiles = 0;
    private String currentFileInProgress;
    private boolean cancelDownload = false;
    private long lastOnlineTime = 0;

    public ArrayList<Runnable> AfterFileDownloadEventHandler = new ArrayList<>();
    public ArrayList<Runnable> BeforeFileDownloadEventHandler = new ArrayList<>();
    public Preferences settings = Preferences.userNodeForPackage(ftpsync.FTPSync.class);
    private final FTPClient ftpClient = new FTPClient();
    private static API instance;

    private API() {

    }

    public static API getInstance() {
        if (API.instance == null) {
            API.instance = new API();
        }
        return API.instance;
    }

    private String Encrypt(String plain) {
        try {
            return HashGenerator.generateMD5(plain);
        } catch (IOException ex) {
            Log.error(ex.toString());
            return null;
        }
    }

    public boolean IsUpdateAvailable() {
        if (FileUtils.isDebugMode()) {
            return false;
        }

        String localHash = API.version;
        String remoteHash = this.CallBackend("lastversion");

        if (!remoteHash.equals(localHash)) {
            Log.info("Update available - Local:" + localHash + ", Remote:" + remoteHash);
            return true;
        } else {
            Log.info("Running latest Build: " + localHash);
            return false;
        }
    }

    public String DownloadApplicationFile() throws IOException {
        try {
            URL website = new URL(this.apiDomain + this.updateRemoteFile + "?" + System.nanoTime());
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
            File downloadFolder = new File(this.tempFilePath);
            downloadFolder.mkdirs();
            FileOutputStream fos = new FileOutputStream(this.tempFilePath + "/" + this.updateLocalFile);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            return this.tempFilePath;
        } catch (FileNotFoundException ex) {
            Log.error(ex.toString());
            Log.remoteError(ex);
        }
        return null;
    }

    private String CallBackend(String method) {
        int retryNum = 0;
        long retryTime = 0;
        if (!method.equals("ping")) {
            while (!this.IsOnline() && retryNum <= 10) {
                retryNum++;
                retryTime = (long) (100 * Math.pow(2, retryNum));
                try {
                    Thread.sleep(retryTime);
                } catch (InterruptedException ex) {
                    Log.remoteError(ex);
                    Log.error("Thread interrupted while waiting for Network");
                }
            }
            if (retryNum > 0) {
                Log.info("Needed " + retryNum + " retries (" + retryTime / 1000 + "sec) for API call (" + method + ")");
                Log.remoteInfo("Needed " + retryNum + " retries (" + retryTime / 1000 + "sec) for API call (" + method + ")");
            }
        }

        String response = "";

        try {
            String request = this.apiURL;
            request += "?q=" + method;
            request += "&u=" + URLDecoder.decode(this.loginName, "UTF-8");
            request += "&p=" + URLDecoder.decode(this.loginPassword, "UTF-8");

            Log.info("Calling API: <" + method + ">");

            URL url = new URL(request);
            InputStreamReader input = new InputStreamReader(url.openStream(), Charset.forName("UTF-8"));
            BufferedReader br = new BufferedReader(input);
            String strTemp = "";
            while (null != (strTemp = br.readLine())) {
                response += strTemp;
            }
        } catch (Exception ex) {
            Log.error(ex.toString());
            Log.remoteError(ex);
        }

        int responseLength = response.length();

        if (responseLength < minimumApiResponseCharsBeforeAlert) {
            Log.warning("Response for API-Call <" + method + "> was very short (" + responseLength + " characters)");
            Log.remoteInfo("Short API-Response for <" + method + "> (" + responseLength + " chars)");
        }

        return response;
    }

    private void GetFTPCredentials() {
        String[] ret = this.CallBackend("ftp").split(this.apiListDivider);
        this.ftpLoginName = ret[0];
        this.ftpLoginPassword = ret[1];
    }

    private String GetFTPLoginName() {
        if (this.ftpLoginName == null) {
            this.GetFTPCredentials();
        }
        return this.ftpLoginName;
    }

    private String GetFTPLoginPassword() {
        if (this.ftpLoginPassword == null) {
            this.GetFTPCredentials();
        }
        return this.ftpLoginPassword;
    }

    private void ConnectFTPClient() {
        Log.info("Connecting to FTP-Server");
        try {
            String[] ftpConnection = this.ftpURL.split(":");
            this.ftpClient.setAutodetectUTF8(true);
            this.ftpClient.connect(ftpConnection[0], Integer.parseInt(ftpConnection[1]));
            this.ftpClient.login(this.GetFTPLoginName(), this.GetFTPLoginPassword());
            this.ftpClient.enterLocalPassiveMode();
            this.ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        } catch (IOException ex) {
            Log.error(ex.toString());
            Log.remoteError(ex);
        }
    }

    private String RemoveTrailingSlash(String start) {
        if (start.substring(start.length() - 1).equals("/")) {
            start = start.substring(0, start.length() - 1);
        }
        return start;
    }

    private void CallDownloadFinishedHandlers() {
        this.AfterFileDownloadEventHandler.forEach((runner) -> runner.run());
    }

    private void CallDownloadStartedHandlers() {
        this.BeforeFileDownloadEventHandler.forEach((runner) -> runner.run());
    }

    public Boolean Login(String loginName, String loginPassword) {
        return this.LoginEncrypted(loginName, this.Encrypt(loginPassword));
    }

    public Boolean LoginEncrypted(String loginName, String loginPassword) {
        this.loginName = loginName;
        this.loginPassword = loginPassword;

        final String expectedResult = "sync_permission";
        String response = this.CallBackend("login");
        this._isAuthenticated = response.equals(expectedResult);

        if (this.IsAuthenticated()) {
            this.settings.put("username", this.loginName);
            this.settings.put("password", this.loginPassword);
        }
        return this.IsAuthenticated();
    }

    public Boolean IsAuthenticated() {
        return this._isAuthenticated;
    }

    public Boolean RefreshAuthState() {
        return this.LoginEncrypted(this.loginName, this.loginPassword);
    }

    public int GetLocalFileCount() {
        return this.currentlyDownloadedFiles;
    }

    public void CancelDownload() {
        this.cancelDownload = true;
    }

    public String GetCurrentProcessedFile() {
        return this.currentFileInProgress;
    }

    public Boolean IsOnline() {
        long currentTime = System.currentTimeMillis();
        long notOnlineSince = currentTime - this.lastOnlineTime;
        boolean isOnline = false;

        if (notOnlineSince < this.millisecondsBetweenOnlineChecks) {
            return true;
        }

        if (this.loginName == null || this.loginPassword == null) {
            this.loginName = this.loginPassword = "DUMMY";
        }
        String expectedResult = "pong";
        String answer = this.CallBackend("ping");
        if (this.loginName == null || this.loginPassword == null) {
            this.loginName = this.loginPassword = null;
        }

        isOnline = answer.equals(expectedResult);

        if (isOnline) {
            this.lastOnlineTime = currentTime;
        }
        return isOnline;
    }

    public String[] GetRemoteFileList() {
        String[] files;
        String ret = this.CallBackend("dirlist");
        files = ret.split(this.apiListDivider);
        Arrays.sort(files);
        return files;
    }

    public int GetRemoteFileCount() {
        return Integer.parseInt(this.CallBackend("numfiles")) - 1;
    }

    public Boolean DownloadSingleFile(String remoteURL, String localURL) {
        OutputStream outputStream = null;
        try {
            if (!this.ftpClient.isConnected()) {
                this.ConnectFTPClient();
            }
            this.CallDownloadStartedHandlers();
            File localFile = new File(localURL);
            Boolean success = false;
            if (!localFile.exists()) {
                Log.info("Downloading <" + remoteURL + "> to <" + localURL + ">");
                String tempURL = this.tempFilePath + remoteURL;
                File tempFile = new File(tempURL);
                File tempPath = new File(tempURL.substring(0, tempURL.lastIndexOf("/")));
                File localPath = new File(localURL.substring(0, localURL.lastIndexOf("/")));
                tempPath.mkdirs();
                localPath.mkdirs();
                outputStream = new BufferedOutputStream(new FileOutputStream(tempFile));
                success = ftpClient.retrieveFile(remoteURL, outputStream);
                outputStream.close();
                if (success) {
                    this.currentlyDownloadedFiles++;
                    Files.move(Paths.get(tempURL), Paths.get(localURL));
                } else {
                    Log.warning("Download failed!");

                }
            } else {
                this.currentlyDownloadedFiles++;
                Log.info("Skipping File <" + remoteURL + ">, already existing");
            }
            this.CallDownloadFinishedHandlers();
            return success;
        } catch (IOException ex) {
            Log.error(ex.toString());
            Log.remoteError(ex);
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException ex) {
                Log.error(ex.toString());
                Log.remoteError(ex);
            }
        }
        return false;

    }

    public void DownloadAllFiles(String localBasePath) {
        this.currentlyDownloadedFiles = 0;
        this.DeleteTempFiles();
        String[] files = this.GetRemoteFileList();
        localBasePath = this.RemoveTrailingSlash(localBasePath);
        for (String file : files) {
            if (this.cancelDownload) {
                Log.warning("Download has been canceled");
                break;
            }
            this.currentFileInProgress = file;
            this.DownloadSingleFile(file, localBasePath + "/" + file);
        }
        this.currentFileInProgress = null;
        if (!this.cancelDownload) {
            this.DeleteTempFiles();
        }
        this.cancelDownload = false;
    }

    public void DeleteNonExistingFiles(String localBasePath) {
        Log.info("Searching for non-existing Files");
        String[] remoteFiles = this.GetRemoteFileList();
        final String finalLocalPath = this.RemoveTrailingSlash(localBasePath);
        AtomicInteger logDeletedFiles = new AtomicInteger();

        try {
            Files.walk(Paths.get(localBasePath)).forEach(filePath -> {
                if (Files.isRegularFile(filePath)) {
                    String file = filePath.toString().replace(finalLocalPath, "");
                    file = file.substring(1);
                    file = file.replace("\\", "/");
                    if (Arrays.binarySearch(remoteFiles, file) < 0) {
                        try {
                            Log.info("Deleting <" + file + ">");
                            Files.delete(filePath);
                            logDeletedFiles.incrementAndGet();
                        } catch (IOException ex) {
                            Log.error("Cant delete File:" + ex.toString());
                            Log.remoteError(ex);
                        }
                    }
                }
            });
            Files.walk(Paths.get(localBasePath)).forEach(filePath -> {
                if (Files.isDirectory(filePath)) {
                    String file = filePath.toString().replace(finalLocalPath + "/", "");
                    try {
                        Files.delete(filePath);
                        Log.info("Deleting empty Directory <" + file + ">");
                    } catch (IOException e) {

                    }
                }
            });
        } catch (IOException ex) {
            Log.error(ex.toString());
        }
        Log.info("Disk-Cleanup finished, deleted " + logDeletedFiles.get() + " Files");
        if (logDeletedFiles.get() > this.nonExistingFilesThreesholdBeforeAlert) {
            Log.remoteInfo("Deleted " + logDeletedFiles.get() + " non-existing Files");
        }

    }

    public void DeleteTempFiles() {
        FileUtils.deleteDirectory(new File(this.tempFilePath));
    }

    public void DeleteLocalData(String localBasePath) {
        Log.info("Deleting local Data!");
        Log.remoteInfo("Deleting local Data!");
        FileUtils.deleteDirectory(new File(localBasePath));
        Log.info("Data Deletion finished");
    }
}
