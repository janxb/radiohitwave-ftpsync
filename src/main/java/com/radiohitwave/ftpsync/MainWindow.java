/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.radiohitwave.ftpsync;

import com.radiohitwave.Log;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.prefs.BackingStoreException;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/**
 *
 * @author broddaja
 */
public final class MainWindow extends javax.swing.JFrame {

	private API api = API.getInstance();
	private int remoteFileCount;
	private Timer syncTimer = new Timer();
	private final int syncRepeatMinutes = 60;
	private Boolean timerScheduled = false;
	private boolean downloadCanceled = false;
	private boolean syncRunning = false;

	private String startSyncButtonText;

	/**
	 * Creates new form MainWindow
	 */
	public MainWindow() {
		initComponents();
		this.setLocationRelativeTo(null);
		this.setIconImage(new ImageIcon(FTPSync.class.getResource("res/icon.png")).getImage());

		this.enableTrayIcon();

		final MainWindow window = this;
		api.BeforeFileDownloadEventHandler.add(() -> {
			window.incrementProgressBar();
			window.currentFileLabel.setText(window.api.GetCurrentProcessedFile());
			window.fileNumLabel.setText(window.api.GetLocalFileCount() + " / " + window.remoteFileCount);
		});
		api.AfterFileDownloadEventHandler.add(() -> {
			window.currentFileLabel.setText("");
		});

		if (this.startSyncButton.isEnabled()) {
			this.startSyncButtonText = this.startSyncButton.getText();
		}

		if (this.api.settings.getBoolean("autosync", false)) {
			this.scheduleSyncTimer();
			this.updateTimerScheduleLabel();
		}
	}

	private boolean enableTrayIcon() {
		if (!SystemTray.isSupported()) {
			return false;
		}

		try {
			URL url = FTPSync.class.getResource("res/icon.png");
			Image img = Toolkit.getDefaultToolkit().getImage(url);
			final TrayIcon trayIcon = new TrayIcon(img, "FTPSync");
			trayIcon.addActionListener((ActionEvent) -> {
				this.setVisible(!this.isVisible());
			});
			trayIcon.setImageAutoSize(true);
			SystemTray.getSystemTray().add(trayIcon);
			return true;
		} catch (AWTException ex) {
			Log.remoteError(ex);
			return false;
		}
	}

	public final void scheduleSyncTimer() {
		final MainWindow window = this;
		if (this.timerScheduled) {
			this.cancelSyncTimer();
		}
		this.syncTimer = new Timer();
		this.syncTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				window.doFullSync();
				window.updateTimerScheduleLabel();
			}
		}, this.syncRepeatMinutes * 60 * 1000, this.syncRepeatMinutes * 60 * 1000);
		this.timerScheduled = true;
	}

	public final void cancelSyncTimer() {
		this.syncTimer.cancel();
		this.timerScheduled = false;
	}

	public void setButtonState(Boolean enabled) {
		this.syncRunning = !enabled;
		this.startSyncButton.setEnabled(true);
		this.pathSelectorButton.setEnabled(enabled);
		this.autoSyncButton.setEnabled(enabled);
		if (enabled) {
			this.startSyncButton.setText(this.startSyncButtonText);
		} else {
			this.startSyncButton.setText("Sync abbrechen!");
		}
	}

	public boolean checkForUpdates() {
		if (api.IsUpdateAvailable()) {
			try {
				JOptionPane.showMessageDialog(this, "Es ist ein Update für FTPSync verfügbar. \n"
						+ "Die neue Version wird jetzt heruntergeladen..");
				String downloadedFile = api.DownloadApplicationFile();
				JOptionPane.showMessageDialog(this,
						"Das Update wurde heruntergeladen. Der Zielordner wird jetzt geöffnet.\n"
								+ "Kopiere dir die neue Version in den gewünschten Ordner,\n"
								+ "und starte das Programm anschließend neu.");
				this.api.CancelDownload();
				this.cancelSyncTimer();
				this.downloadCanceled = true;
				if (Desktop.isDesktopSupported()) {
					Desktop.getDesktop().open(new File(downloadedFile));
				}
				return true;
			} catch (IOException ex) {
				Log.error(ex.toString());
				Log.remoteError(ex);
			}
		}
		return false;
	}

	public void doFullSync() {
		if (!api.IsOnline()) {
			Log.warning("No Internet Connection..");
			return;
		}

		if (this.checkForUpdates()) {
			this.dispose();
			System.exit(0);
			return;
		}

		this.downloadCanceled = false;
		this.filePathLabel.setText(this.api.settings.get("localpath", ""));
		this.setButtonState(false);
		final MainWindow window = this;
		Thread thread = new Thread() {
			@Override
			public void run() {
				if (window.api.settings.get("localpath", null) != null) {
					File dir = new File(window.api.settings.get("localpath", null));
					if (!dir.exists()) {
						dir.mkdirs();
					}
					Log.remoteInfo("Synchronisation gestartet.");
					window.setStatus("Hole Server-Informationen");
					if (!window.api.RefreshAuthState()) {
						Log.warning("User not authenticated, deleting local Data");
						window.api.DeleteLocalData(window.api.settings.get("localpath", null));
						JOptionPane.showMessageDialog(window,
								"Dein Account ist nicht für FTPSync freigeschaltet!\nAlle lokalen Dateien wurden gelöscht.\nBitte wende dich an den zuständigen Administrator");
					}
					Log.info("Starting full Sync");
					window.remoteFileCount = window.api.GetRemoteFileCount();
					window.progressBar.setMaximum(window.remoteFileCount);
					window.progressBar.setValue(0);
					if (!window.downloadCanceled) {
						window.setStatus("Lade Dateien herunter");
						window.api.DownloadAllFiles(dir.getAbsolutePath());
						window.currentFileLabel.setText(" ");
					}
					if (!window.downloadCanceled) {
						window.setStatus("Lösche überflüssige Dateien");
						window.api.DeleteNonExistingFiles(dir.getAbsolutePath());
					}
					Log.remoteInfo("Synchronisation beendet.");
					window.setStatus("Synchronisation abgeschlossen");
				} else {
					Log.warning("No local sync Path found");
					JOptionPane.showMessageDialog(window,
							"Du hast noch keinen Speicherort festgelegt.\nBitte über den Button unten links auswählen..",
							"Lokaler Speicherort nicht ausgewählt", JOptionPane.INFORMATION_MESSAGE);
				}
				window.setButtonState(true);
			}
		};

		thread.start();
	}

	public void setStatus(String status) {
		this.statusLabel.setText(status);
	}

	public void incrementProgressBar() {
		this.progressBar.setValue(this.progressBar.getValue() + 1);
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
	// <editor-fold defaultstate="collapsed" desc="Generated
	// Code">//GEN-BEGIN:initComponents
	private void initComponents() {

		progressBar = new javax.swing.JProgressBar();
		jLabel3 = new javax.swing.JLabel();
		filePathLabel = new javax.swing.JLabel();
		pathSelectorButton = new javax.swing.JButton();
		fileNumLabel = new javax.swing.JLabel();
		statusLabel = new javax.swing.JLabel();
		currentFileLabel = new javax.swing.JLabel();
		autoSyncLabel = new javax.swing.JLabel();
		startSyncButton = new javax.swing.JButton();
		autoSyncButton = new javax.swing.JButton();
		logoutButton = new javax.swing.JButton();

		setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
		setTitle("Radio Hitwave FTPSync");
		setMaximumSize(null);
		setResizable(false);
		addWindowListener(new java.awt.event.WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent evt) {
				formWindowClosing(evt);
			}

			public void windowOpened(java.awt.event.WindowEvent evt) {
				formWindowOpened(evt);
			}
		});
		getContentPane().setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

		progressBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
		getContentPane().add(progressBar, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 70, 380, -1));

		jLabel3.setFont(new java.awt.Font("Droid Sans", 1, 22)); // NOI18N
		jLabel3.setText("Radio Hitwave FTPSync");
		getContentPane().add(jLabel3, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 0, -1, -1));

		filePathLabel.setText("           ");
		getContentPane().add(filePathLabel, new org.netbeans.lib.awtextra.AbsoluteConstraints(170, 185, -1, -1));

		pathSelectorButton.setText("Speicherort ändern");
		pathSelectorButton.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				pathSelectorButtonActionPerformed(evt);
			}
		});
		getContentPane().add(pathSelectorButton, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 180, 150, -1));

		fileNumLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		fileNumLabel.setText("                 ");
		fileNumLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
		getContentPane().add(fileNumLabel, new org.netbeans.lib.awtextra.AbsoluteConstraints(310, 95, 80, -1));

		statusLabel.setText("                        ");
		getContentPane().add(statusLabel, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 95, -1, -1));

		currentFileLabel.setText("              ");
		getContentPane().add(currentFileLabel, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 50, -1, -1));

		autoSyncLabel.setText("           ");
		getContentPane().add(autoSyncLabel, new org.netbeans.lib.awtextra.AbsoluteConstraints(170, 155, -1, -1));

		startSyncButton.setText("Manueller Download");
		startSyncButton.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				startSyncButtonActionPerformed(evt);
			}
		});
		getContentPane().add(startSyncButton, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 120, 150, -1));

		autoSyncButton.setText("Autom. Synchronisation");
		autoSyncButton.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				autoSyncButtonActionPerformed(evt);
			}
		});
		getContentPane().add(autoSyncButton, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 150, 150, -1));

		logoutButton.setText("Abmelden");
		logoutButton.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				logoutButtonActionPerformed(evt);
			}
		});
		getContentPane().add(logoutButton, new org.netbeans.lib.awtextra.AbsoluteConstraints(310, 5, -1, -1));

		setBounds(0, 0, 415, 249);
	}// </editor-fold>//GEN-END:initComponents

	private void formWindowOpened(java.awt.event.WindowEvent evt) {// GEN-FIRST:event_formWindowOpened
		this.doFullSync();
	}// GEN-LAST:event_formWindowOpened

	private void pathSelectorButtonActionPerformed(java.awt.event.ActionEvent evt) {// GEN-FIRST:event_pathSelectorButtonActionPerformed
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int chooserResult = chooser.showDialog(this, "Speicherort auswählen");
		if (chooserResult == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile().exists()) {
			this.api.settings.put("localpath", chooser.getSelectedFile().getAbsolutePath());
			this.doFullSync();
		}
	}// GEN-LAST:event_pathSelectorButtonActionPerformed

	public void updateTimerScheduleLabel() {
		Calendar futureTime = Calendar.getInstance();
		futureTime.add(Calendar.MINUTE, this.syncRepeatMinutes);
		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
		String timeString = timeFormat.format(futureTime.getTime());
		this.autoSyncLabel.setText("Nächster Download um " + timeString);
	}

	private void startSyncButtonActionPerformed(java.awt.event.ActionEvent evt) {// GEN-FIRST:event_startSyncButtonActionPerformed
		if (!this.syncRunning) {
			this.doFullSync();
		} else {
			this.startSyncButton.setText("Warte kurz..");
			this.startSyncButton.setEnabled(false);
			this.api.CancelDownload();
			this.downloadCanceled = true;
		}
	}// GEN-LAST:event_startSyncButtonActionPerformed

	private void autoSyncButtonActionPerformed(java.awt.event.ActionEvent evt) {// GEN-FIRST:event_autoSyncButtonActionPerformed
		if (this.timerScheduled) {
			this.autoSyncLabel.setText(" ");
			this.cancelSyncTimer();
			this.api.settings.putBoolean("autosync", false);
		} else {
			this.scheduleSyncTimer();
			this.updateTimerScheduleLabel();
			this.api.settings.putBoolean("autosync", true);
		}
	}// GEN-LAST:event_autoSyncButtonActionPerformed

	private void logoutButtonActionPerformed(java.awt.event.ActionEvent evt) {// GEN-FIRST:event_logoutButtonActionPerformed
		try {
			this.api.CancelDownload();
			this.cancelSyncTimer();
			this.downloadCanceled = true;
			this.api.settings.clear();
			this.dispose();
			System.exit(0);
			LoginWindow.main(new String[0]);
		} catch (BackingStoreException ex) {
			Log.remoteError(ex);
		}
	}// GEN-LAST:event_logoutButtonActionPerformed

	private void formWindowClosing(java.awt.event.WindowEvent evt) {// GEN-FIRST:event_formWindowClosing
		this.downloadCanceled = true;
		this.api.CancelDownload();
		this.api.DeleteTempFiles();
	}// GEN-LAST:event_formWindowClosing

	/**
	 * @param args
	 *            the command line arguments
	 */
	public static void main(String args[]) {
		/* Create and display the form */
		java.awt.EventQueue.invokeLater(new Runnable() {
			public void run() {
				new MainWindow().setVisible(true);
			}
		});
	}

	// Variables declaration - do not modify//GEN-BEGIN:variables
	private javax.swing.JButton autoSyncButton;
	private javax.swing.JLabel autoSyncLabel;
	private javax.swing.JLabel currentFileLabel;
	private javax.swing.JLabel fileNumLabel;
	private javax.swing.JLabel filePathLabel;
	private javax.swing.JLabel jLabel3;
	private javax.swing.JButton logoutButton;
	private javax.swing.JButton pathSelectorButton;
	private javax.swing.JProgressBar progressBar;
	private javax.swing.JButton startSyncButton;
	private javax.swing.JLabel statusLabel;
	// End of variables declaration//GEN-END:variables
}
