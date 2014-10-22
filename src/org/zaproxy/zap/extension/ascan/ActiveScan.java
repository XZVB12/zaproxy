/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2013 The ZAP development team
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.  
 */
package org.zaproxy.zap.extension.ascan;

import java.awt.EventQueue;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.DefaultListModel;

import org.apache.log4j.Logger;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.HostProcess;
import org.parosproxy.paros.core.scanner.PluginFactory;
import org.parosproxy.paros.core.scanner.ScannerListener;
import org.parosproxy.paros.core.scanner.ScannerParam;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.model.SiteMap;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.ConnectionParam;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.model.Context;
import org.zaproxy.zap.model.GenericScanner;
import org.zaproxy.zap.users.User;
import org.zaproxy.zap.view.ScanPanel;

public class ActiveScan extends org.parosproxy.paros.core.scanner.Scanner implements GenericScanner, ScannerListener {
	
	public static enum State {
		NOT_STARTED,
		RUNNING,
		PAUSED,
		FINISHED
	};

	private int id;
	private String site = null;
	private ActiveScanPanel activeScanPanel;
	private int progress = 0;
	private boolean isAlive = false;
	private ActiveScanTableModel messagesTableModel = new ActiveScanTableModel();
	private SiteNode startNode = null;
	private Context startContext = null;
	private AtomicInteger totalRequests = new AtomicInteger(0);
	private Date timeStarted = null;
	private Date timeFinished = null;
	private int maxResultsToList = 0;
	private State state = State.NOT_STARTED;

	private final List<Integer> hRefs = Collections.synchronizedList(new ArrayList<Integer>());
	private final List<Integer> alerts = Collections.synchronizedList(new ArrayList<Integer>());

	private static final Logger log = Logger.getLogger(ActiveScan.class);

	public ActiveScan(String site, ScannerParam scannerParam, 
			ConnectionParam param, ActiveScanPanel activeScanPanel) {
		this(site, scannerParam, param, activeScanPanel, null);
	}

	public ActiveScan(String site, ScannerParam scannerParam, 
			ConnectionParam param, ActiveScanPanel activeScanPanel, PluginFactory pluginFactory) {
		super(scannerParam, param, pluginFactory);
		this.site = site;
		this.maxResultsToList = scannerParam.getMaxResultsToList();
		if (activeScanPanel != null) {
			this.activeScanPanel = activeScanPanel;
			this.addScannerListener(activeScanPanel);
		}
		// Easiest way to get the messages and alerts ;) 
		this.addScannerListener(this);
	
	}

	@Override
	public int getMaximum() {
		return 100;
	}

	@Override
	public int getProgress() {
		return progress;
	}

	@Override
	public String getSite() {
		return site;
	}

	@Override
	public boolean isRunning() {
		return isAlive;
	}

	@Override
	public boolean isStopped() {
		return super.isStop();
	}

	@Override
	public void pauseScan() {
		if (this.isRunning()) {
			super.pause();
			this.state = State.PAUSED;
		}
	}

	@Override
	public void start() {
		isAlive = true;
		this.timeStarted = new Date();
		if (startNode == null) {
			SiteMap siteTree = Model.getSingleton().getSession().getSiteTree();
			if (this.getJustScanInScope()) {
				startNode = (SiteNode) siteTree.getRoot();
			} else {
				SiteNode rootNode = (SiteNode) siteTree.getRoot();
				@SuppressWarnings("unchecked")
				Enumeration<SiteNode> en = rootNode.children();
				while (en.hasMoreElements()) {
					SiteNode sn = en.nextElement();
					String nodeName = ScanPanel.cleanSiteName(sn.getNodeName(), true);
					if (this.site.equals(nodeName)) {
						startNode = sn;
						break;
					}
				}
			}
		}
		reset();
		this.progress = 0;
		if (startNode != null) {
			this.start(startNode);
			this.state = State.RUNNING;
		} else {
			log.error("Failed to find site " + site);
		}
	}

	@Override
	public void stopScan() {
		super.stop();
		this.state = State.FINISHED;
	}

	@Override
	public void resumeScan() {
		if (this.isPaused()) {
			super.resume();
			this.state = State.RUNNING;
		}
	}

	@Override
	public void alertFound(Alert alert) {
		int alertId = alert.getAlertId();
		if (alertId != -1) {
			alerts.add(Integer.valueOf(alert.getAlertId()));
		}
	}

	@Override
	public void hostComplete(String hostAndPort) {
		if (activeScanPanel != null) {
			// Probably being run from the API
			this.activeScanPanel.scanFinshed(hostAndPort);
			this.removeScannerListener(activeScanPanel);
		}
		isAlive = false;
	}

	@Override
	public void hostNewScan(String hostAndPort, HostProcess hostThread) {
	}

	@Override
	public void hostProgress(String hostAndPort, String msg, int percentage) {
		this.progress = percentage;
	}

	@Override
	public void scannerComplete() {
		this.timeFinished = new Date();
		this.state = State.FINISHED;
	}

	@Override
	public DefaultListModel<HistoryReference> getList() {
		return null;
	}

	public ActiveScanTableModel getMessagesTableModel() {
	    return messagesTableModel;
	}
	
	@Override
	public void notifyNewMessage(final HttpMessage msg) {
		HistoryReference hRef = msg.getHistoryRef();
		if (hRef == null) {
			try {
				hRef = new HistoryReference(
						Model.getSingleton().getSession(),
						HistoryReference.TYPE_SCANNER_TEMPORARY,
						msg);
				msg.setHistoryRef(null);
				hRefs.add(Integer.valueOf(hRef.getHistoryId()));
			} catch (HttpMalformedHeaderException | SQLException e) {
				log.error(e.getMessage(), e);
			}
		} else {
			hRefs.add(Integer.valueOf(hRef.getHistoryId()));
		}
		
        if (hRef != null && this.totalRequests.incrementAndGet() <= this.maxResultsToList) {
            // Very large lists significantly impact the UI responsiveness
            // limiting them makes large scans _much_ quicker
                addHistoryReference(hRef);
    	}
	}

    private void addHistoryReference(HistoryReference hRef) {
        if (View.isInitialised()) {
            addHistoryReferenceInEdt(hRef);
        } else {
            synchronized (messagesTableModel) {
                messagesTableModel.addHistoryReference(hRef);
            }
        }
	}

    private void addHistoryReferenceInEdt(final HistoryReference hRef) {
        if (EventQueue.isDispatchThread()) {
            messagesTableModel.addHistoryReference(hRef);
        } else {
            EventQueue.invokeLater(new Runnable() {

                @Override
                public void run() {
                    addHistoryReference(hRef);
                }
            });
        }
	}
	
	@Override
	public SiteNode getStartNode() {
		return this.startNode;
	}

	@Override
	public void setStartNode(SiteNode startNode) {
		this.startNode = startNode;
		super.setStartNode(startNode);
	}

	@Override
	public void reset() {
	    if (!View.isInitialised() || EventQueue.isDispatchThread()) {
	        this.messagesTableModel.clear();
        } else {
            EventQueue.invokeLater(new Runnable() {

                @Override
                public void run() {
                    reset();
                }
            });
        }
	}

	@Override
	public void setScanContext(Context context) {
		this.startContext=context;		
		//TODO: Use this context to start the active scan only on Nodes in scope
	}

	public int getTotalRequests() {
		return totalRequests.intValue();
	}

	public Date getTimeStarted() {
		return timeStarted;
	}

	public Date getTimeFinished() {
		return timeFinished;
	}

	@Override
	public void setScanAsUser(User user) {
		this.setUser(user);
	}


	/**
	 * Returns the IDs of all messages sent/created during the scan. The message must be recreated with a HistoryReference.
	 * <p>
	 * <strong>Note:</strong> Iterations must be {@code synchronized} on returned object. Failing to do so might result in
	 * {@code ConcurrentModificationException}.
	 * </p>
	 *
	 * @return the IDs of all the messages sent/created during the scan
	 * @see HistoryReference
	 * @see ConcurrentModificationException
	 */
	public List<Integer> getMessagesIds() {
		return hRefs;
	}

	/**
	 * Returns the IDs of all alerts raised during the scan.
	 * <p>
	 * <strong>Note:</strong> Iterations must be {@code synchronized} on returned object. Failing to do so might result in
	 * {@code ConcurrentModificationException}.
	 * </p>
	 *
	 * @return the IDs of all the alerts raised during the scan
	 * @see ConcurrentModificationException
	 */
	public List<Integer> getAlertsIds() {
		return alerts;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public State getState() {
		return state;
	}
}
