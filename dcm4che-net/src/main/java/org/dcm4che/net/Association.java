/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.net.pdu.AAbort;
import org.dcm4che.net.pdu.AAssociateAC;
import org.dcm4che.net.pdu.AAssociateRJ;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.net.pdu.RoleSelection;
import org.dcm4che.net.PDUEncoder;
import org.dcm4che.util.IntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class Association {

    static final Logger LOG =
            LoggerFactory.getLogger(Association.class);

    private static final AtomicInteger prevSerialNo = new AtomicInteger();
    private final AtomicInteger messageID = new AtomicInteger();
    private final int serialNo;
    private final boolean requestor;
    private String name;
    private ApplicationEntity ae;
    private final Device device;
    private final Connection conn;
    private Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final PDUEncoder encoder;
    private PDUDecoder decoder;
    private State state;
    private AAssociateRQ rq;
    private AAssociateAC ac;
    private IOException ex;
    private HashMap<String, Object> properties;
    private int maxOpsInvoked;
    private int maxPDULength;
    private AssociationCloseListener asListener;
    private final IntHashMap<DimseRSPHandler> rspHandlerForMsgId =
            new IntHashMap<DimseRSPHandler>();
    private final HashMap<String,HashMap<String,PresentationContext>> pcMap =
            new HashMap<String,HashMap<String,PresentationContext>>();

    Association(Connection local, Socket sock, boolean requestor)
            throws IOException {
        this.serialNo = prevSerialNo.incrementAndGet();
        this.requestor = requestor;
        this.name = "Association" + delim() + serialNo;
        this.conn = local;
        this.device = local.getDevice();
        this.sock = sock;
        this.in = sock.getInputStream();
        this.out = sock.getOutputStream();
        this.encoder = new PDUEncoder(this, out);
        enterState(requestor ? State.Sta4 : State.Sta2);
    }

    private int nextMessageID() {
        return messageID.incrementAndGet() & 0xFFFF;
    }

    private char delim() {
        return requestor ? '-' : '+';
    }

    @Override
    public String toString() {
        return name;
    }

    public final Socket getSocket() {
        return sock;
    }

    public final AAssociateRQ getAAssociateRQ() {
        return rq;
    }

    public final AAssociateAC getAAssociateAC() {
        return ac;
    }

    public final ApplicationEntity getApplicationEntity() {
        return ae;
    }

    final void setApplicationEntity(ApplicationEntity ae) {
        this.ae = ae;
    }

    public final AssociationCloseListener getAAssociationCloseListener() {
        return asListener;
    }

    public final void setAssociationCloseListener(
            AssociationCloseListener listener) {
        this.asListener = listener;
    }

    public Object getProperty(String key) {
        return properties != null ? properties.get(key) : null;
    }

    public Object setProperty(String key, Object value) {
        if (properties == null)
            properties = new HashMap<String, Object>();
        return properties.put(key, value);
    }

    public Object clearProperty(String key) {
        return properties != null ? properties.remove(key) : null;
    }

    public final boolean isRequestor() {
        return requestor;
    }

    final IOException getException() {
        return ex;
    }

    private boolean isSCPFor(String cuid) {
        RoleSelection rolsel = ac.getRoleSelectionFor(cuid);
        if (rolsel == null)
            return !requestor;
        return requestor ? rolsel.isSCP() : rolsel.isSCU();
    }

    private boolean isSCUFor(String cuid) {
        RoleSelection rolsel = ac.getRoleSelectionFor(cuid);
        if (rolsel == null)
            return requestor;
        return requestor ? rolsel.isSCU() : rolsel.isSCP();
    }

    public String getCallingAET() {
        return rq != null ? rq.getCallingAET() : null;
    }

    public String getCalledAET() {
        return rq != null ? rq.getCalledAET() : null;
    }

    public String getRemoteAET() {
        return requestor ? getCalledAET() : getCallingAET();
    }

    public String getLocalAET() {
        return requestor ? getCallingAET() : getCalledAET();
    }

    final int getMaxPDULengthSend() {
        return maxPDULength;
    }

    boolean isPackPDV() {
        return ae.isPackPDV();
    }

    public void release() throws IOException {
        if (!isRequestor())
            throw new IllegalStateException("is not association-requestor");
        state.writeAReleaseRQ(this);
    }

    public void abort(AAbort aa) {
        state.write(this, aa);
    }

    void write(AAbort aa) {
        LOG.info("{} << {}", name, aa);
        enterState(State.Sta13);
        try {
            encoder.write(aa);
        } catch (IOException e) {
            LOG.debug("{}: failed to write {}", name, aa);
        }
        ex = aa;
    }

    void writeAReleaseRQ() throws IOException {
        LOG.info("{} << A-RELEASE-RQ", name);
        enterState(State.Sta7);
        encoder.writeAReleaseRQ();
        startARTIM(conn.getReleaseTimeout());
    }

    public void waitForOutstandingRSP() {
        // TODO Auto-generated method stub
        
    }

    void write(AAssociateRQ rq) throws IOException {
        name = rq.getCalledAET() + delim() + serialNo;
        this.rq = rq;
        LOG.info("{} << A-ASSOCIATE-RQ", name);
        LOG.debug("{}", rq);
        enterState(State.Sta5);
        encoder.write(rq);
    }

    private void write(AAssociateAC ac) throws IOException {
        LOG.info("{} << A-ASSOCIATE-AC", name);
        LOG.debug("{}", ac);
        enterState(State.Sta6);
        encoder.write(ac);
    }

    private void write(AAssociateRJ e) throws IOException {
        Association.LOG.info("{} << {}", name, e);
        enterState(State.Sta13);
        encoder.write(e);
    }

    void startARTIM(int timeout) throws IOException {
        LOG.debug("{}: start ARTIM {}ms", name, timeout);
        sock.setSoTimeout(timeout);
    }

    private void stopARTIM() throws IOException {
        sock.setSoTimeout(0);
        LOG.debug("{}: stop ARTIM", name);
    }

    private void checkException() throws IOException {
        if (ex != null)
            throw ex;
    }

    private synchronized void enterState(State newState) {
        LOG.debug("{}: enter state: {}", name, newState);
        this.state = newState;
        notifyAll();
    }

    synchronized void waitForLeaving(State state)
            throws InterruptedException, IOException {
        while (this.state == state)
            wait();
        checkException();
    }

    void activate() {
        if (!(state == State.Sta2 || state == State.Sta5))
                throw new IllegalStateException("state: " + state);

        device.execute(new Runnable() {

            @Override
            public void run() {
                decoder = new PDUDecoder(Association.this, in);
                device.incrementNumberOfOpenConnections();
                try {
                    while (!(state == State.Sta1 || state == State.Sta13))
                        decoder.nextPDU();
                } catch (AAbort aa) {
                    abort(aa);
                } catch (SocketTimeoutException e) {
                    ex = e;
                    LOG.warn("{}: ARTIM timer expired in State: {}",
                            name, state);
                } catch (IOException e) {
                    ex = e;
                    LOG.warn("{}: i/o exception: {} in State: {}",
                            new Object[] { name, e, state });
                } finally {
                    device.decrementNumberOfOpenConnections();
                    closeSocket();
                }
            }
        });
    }

    private void closeSocket() {
        if (sock == null)
            return;

        if (state == State.Sta13) {
            try {
                Thread.sleep(conn.getSocketCloseDelay());
            } catch (InterruptedException e) {
                LOG.warn("Interrupted Socket Close Delay", e);
            }
        }
        try { out.close(); } catch (IOException ignore) {}
        try { in.close(); } catch (IOException ignore) {}
        if (sock != null) {
            LOG.info("{}: close {}", name, sock);
            try { sock.close(); } catch (IOException ignore) {}
            sock = null;
            rspHandlerForMsgId.accept(
                    new IntHashMap.Visitor<DimseRSPHandler>(){

                        @Override
                        public boolean visit(int key,
                                DimseRSPHandler rspHandler) {
                            rspHandler.onClose(Association.this);
                            return true;
                        }
                    });
            if (asListener != null)
                asListener.onClose(this);
        }
        enterState(State.Sta1);
    }

    void onAAssociateRQ(AAssociateRQ rq) throws IOException {
        LOG.info("{} >> A-ASSOCIATE-RQ", name);
        LOG.debug("{}", rq);
        state.onAAssociateRQ(this, rq);
    }

    void handle(AAssociateRQ rq) throws IOException {
        this.rq = rq;
        name = rq.getCallingAET() + delim() + serialNo;
        stopARTIM();
        enterState(State.Sta3);
        try {
            if ((rq.getProtocolVersion() & 1) == 0)
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_PROVIDER_ACSE,
                        AAssociateRJ.REASON_PROTOCOL_VERSION_NOT_SUPPORTED);
            if (!rq.getApplicationContext().equals(
                    UID.DICOMApplicationContextName))
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_APP_CTX_NAME_NOT_SUPPORTED);
            ae = device.getApplicationEntity(rq.getCalledAET());
            if (ae == null)
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED);
            ac = ae.negotiate(this, rq);
            initPCMap();
            maxOpsInvoked = ac.getMaxOpsPerformed();
            maxPDULength = ApplicationEntity.minZeroAsMax(
                    rq.getMaxPDULength(), ae.getMaxPDULengthSend());
            write(ac);
        } catch (AAssociateRJ e) {
            write(e);
        }
    }

    void onAAssociateAC(AAssociateAC ac) throws IOException {
        LOG.info("{} >> A-ASSOCIATE-AC", name);
        LOG.debug("{}", ac);
        state.onAAssociateAC(this, ac);
    }

    void handle(AAssociateAC ac) throws IOException {
        this.ac = ac;
        initPCMap();
        maxOpsInvoked = ac.getMaxOpsInvoked();
        maxPDULength = ApplicationEntity.minZeroAsMax(
                ac.getMaxPDULength(), ae.getMaxPDULengthSend());
        stopARTIM();
        enterState(State.Sta6);
    }

    void onAAssociateRJ(AAssociateRJ rj) throws IOException {
        LOG.info("{} >> {}", name, rj);
        state.onAAssociateRJ(this, rj);
    }

    void handle(AAssociateRJ rq) {
        ex = rq;
        closeSocket();
    }

    void onAReleaseRQ() throws IOException {
        LOG.info("{} >> A-RELEASE-RQ", name);
        state.onAReleaseRQ(this);
    }

    void handleAReleaseRQ() throws IOException {
        enterState(State.Sta8);
        LOG.info("{} << A-RELEASE-RP", name);
        enterState(State.Sta13);
        encoder.writeAReleaseRP();
    }

    void handleAReleaseRQCollision() throws IOException {
        if (isRequestor()) {
            enterState(State.Sta9);
            LOG.info("{} << A-RELEASE-RP", name);
            enterState(State.Sta11);
            encoder.writeAReleaseRP();
       } else {
            enterState(State.Sta10);
            try {
                waitForLeaving(State.Sta10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            enterState(State.Sta13);
        }
    }

    void onAReleaseRP() throws IOException {
        LOG.info("{} >> A-RELEASE-RP", name);
        state.onAReleaseRP(this);
    }

    void handleAReleaseRP() throws IOException {
        stopARTIM();
        closeSocket();
    }

    void handleAReleaseRPCollision() throws IOException {
        stopARTIM();
        enterState(State.Sta12);
    }

    void onAAbort(AAbort aa) {
        LOG.info("{} << {}", name, aa);
        ex = aa;
        closeSocket();
    }

    void unexpectedPDU(String pdu) throws AAbort {
        LOG.warn("{} >> unexpected {} in state: {}",
                new Object[] { name, pdu, state });
        throw new AAbort(AAbort.UL_SERIVE_PROVIDER, AAbort.UNEXPECTED_PDU);
    }

    void onPDataTF() throws IOException {
        state.onPDataTF(this);
    }

    void handlePDataTF() throws IOException {
        decoder.decodeDIMSE();
    }

    void writePDataTF() throws IOException {
        state.writePDataTF(this);
    }

    void doWritePDataTF() throws IOException {
        encoder.writePDataTF();
    }

    void onDimseRQ(int pcid, Attributes cmd, PDVInputStream data,
            String tsuid) {
        // TODO Auto-generated method stub
        
    }

    void onDimseRSP(Attributes cmd, Attributes data) throws AAbort {
        int msgId = cmd.getInt(Tag.MessageIDBeingRespondedTo, -1);
        DimseRSPHandler rspHandler = getDimseRSPHandler(msgId);
        if (rspHandler == null) {
            LOG.info("{}: unexpected message ID in DIMSE RSP:", name);
            LOG.info("{}", cmd);
            throw new AAbort();
        }
        try {
            rspHandler.onDimseRSP(this, cmd, data);
        } finally {
            if (!CommandUtils.isPendingRSP(cmd)) {
                updateIdleTimeout();
                removeDimseRSPHandler(msgId);
            } else {
                int cmdfield = cmd.getInt(Tag.CommandField, -1);
                int timeout = cmdfield == CommandUtils.C_GET_RSP
                        ? conn.getCGetRSPTimeout()
                        : cmdfield == CommandUtils.C_MOVE_RSP
                        ? conn.getCMoveRSPTimeout()
                        : conn.getDimseRSPTimeout();
                rspHandler.setTimeout(timeout == 0
                        ? 0
                        : (System.currentTimeMillis() + timeout));
            }
        }
    }

    private void addDimseRSPHandler(int msgId, DimseRSPHandler rspHandler)
            throws InterruptedException {
        synchronized (rspHandlerForMsgId) {
            while (maxOpsInvoked > 0
                    && rspHandlerForMsgId.size() >= maxOpsInvoked)
                rspHandlerForMsgId.wait();
            rspHandlerForMsgId.put(msgId, rspHandler);
        }
    }

    private DimseRSPHandler getDimseRSPHandler(int msgId) {
        synchronized (rspHandlerForMsgId ) {
            return rspHandlerForMsgId.get(msgId);
        }
    }

    private void removeDimseRSPHandler(int msgId) {
        synchronized (rspHandlerForMsgId ) {
            rspHandlerForMsgId.remove(msgId);
            rspHandlerForMsgId.notifyAll();
        }
    }

    private void updateIdleTimeout() {
        // TODO Auto-generated method stub
        
    }

    public void cancel(int pcid, int msgId) {
        // TODO Auto-generated method stub
        
    }

    private void initPCMap() {
        for (PresentationContext pc : ac.getPresentationContexts())
            if (pc.isAccepted())
                initTSMap(rq.getPresentationContext(pc.getPCID())
                            .getAbstractSyntax())
                        .put(pc.getTransferSyntax(), pc);
    }

    private HashMap<String, PresentationContext> initTSMap(String as) {
        HashMap<String, PresentationContext> tsMap = pcMap.get(as);
        if (tsMap == null)
            pcMap.put(as, tsMap = new HashMap<String, PresentationContext>());
        return tsMap;
    }

    private PresentationContext pcFor(String cuid, String tsuid)
            throws NoPresentationContextException {
        HashMap<String, PresentationContext> tsMap = pcMap.get(cuid);
        if (tsMap == null)
            throw new NoPresentationContextException(cuid);
        if (tsuid == null)
            return tsMap.values().iterator().next();
        PresentationContext pc = tsMap.get(tsuid);
        if (pc == null)
            throw new NoPresentationContextException(cuid, tsuid);
        return pc;
    }

    public DimseRSP cecho() throws IOException, InterruptedException {
        return cecho(UID.VerificationSOPClass);
    }

    public DimseRSP cecho(String cuid)
            throws IOException, InterruptedException {
        FutureDimseRSP rsp = new FutureDimseRSP();
        PresentationContext pc = pcFor(cuid, null);
        if (!isSCUFor(cuid))
            throw new NoRoleSelectionException(cuid,
                    TransferCapability.Role.SCP);
        int msgId = nextMessageID();
        Attributes cechorq = CommandUtils.mkCEchoRQ(msgId, cuid);
        invoke(pc, msgId, cechorq, null, rsp,
                conn.getDimseRSPTimeout());
        return rsp;
    }

    private void invoke(PresentationContext pc, int msgId, Attributes cmd,
            DataWriter data, DimseRSPHandler rspHandler, int rspTimeout)
            throws IOException, InterruptedException {
        checkException();
        rspHandler.setPcid(pc.getPCID());
        rspHandler.setMsgId(msgId);
        addDimseRSPHandler(msgId, rspHandler);
        encoder.writeDIMSE(pc, cmd, data);
        rspHandler.setTimeout(System.currentTimeMillis() + rspTimeout);
    }
}
