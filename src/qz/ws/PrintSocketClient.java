package qz.ws;

import jssc.SerialPortException;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.auth.RequestState;
import qz.common.Constants;
import qz.common.TrayManager;
import qz.communication.*;
import qz.printer.PrintServiceMatcher;
import qz.printer.status.StatusMonitor;
import qz.printer.status.StatusSession;
import qz.utils.*;

import javax.management.ListenerNotFoundException;
import javax.print.PrintServiceLookup;
import javax.security.cert.CertificateParsingException;
import javax.usb.util.UsbUtil;
import java.awt.*;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Semaphore;


@WebSocket
public class PrintSocketClient {

    private static final Logger log = LoggerFactory.getLogger(PrintSocketClient.class);

    private final TrayManager trayManager = PrintSocketServer.getTrayManager();
    private static final Semaphore dialogAvailable = new Semaphore(1, true);

    //websocket port -> Connection
    private static final HashMap<Integer,SocketConnection> openConnections = new HashMap<>();

    private enum Method {
        PRINTERS_GET_DEFAULT("printers.getDefault", true, "access connected printers"),
        PRINTERS_FIND("printers.find", true, "access connected printers"),
        PRINTERS_DETAIL("printers.detail", true, "access connected printers"),
        PRINTERS_START_LISTENING("printers.startListening", true, "listen for printer status"),
        PRINTERS_GET_STATUS("printers.getStatus", false),
        PRINTERS_STOP_LISTENING("printers.stopListening", false),
        PRINT("print", true, "print to %s"),

        SERIAL_FIND_PORTS("serial.findPorts", true, "access serial ports"),
        SERIAL_OPEN_PORT("serial.openPort", true, "open a serial port"),
        SERIAL_SEND_DATA("serial.sendData", true, "send data over a serial port"),
        SERIAL_CLOSE_PORT("serial.closePort", true, "close a serial port"),

        USB_LIST_DEVICES("usb.listDevices", true, "access USB devices"),
        USB_LIST_INTERFACES("usb.listInterfaces", true, "access USB devices"),
        USB_LIST_ENDPOINTS("usb.listEndpoints", true, "access USB devices"),
        USB_CLAIM_DEVICE("usb.claimDevice", true, "claim a USB device"),
        USB_CLAIMED("usb.isClaimed", false, "check USB claim status"),
        USB_SEND_DATA("usb.sendData", true, "use a USB device"),
        USB_READ_DATA("usb.readData", true, "use a USB device"),
        USB_OPEN_STREAM("usb.openStream", true, "use a USB device"),
        USB_CLOSE_STREAM("usb.closeStream", false, "use a USB device"),
        USB_RELEASE_DEVICE("usb.releaseDevice", false, "release a USB device"),

        HID_LIST_DEVICES("hid.listDevices", true, "access USB devices"),
        HID_START_LISTENING("hid.startListening", true, "listen for USB devices"),
        HID_STOP_LISTENING("hid.stopListening", false),
        HID_CLAIM_DEVICE("hid.claimDevice", true, "claim a USB device"),
        HID_CLAIMED("hid.isClaimed", false, "check USB claim status"),
        HID_SEND_DATA("hid.sendData", true, "use a USB device"),
        HID_READ_DATA("hid.readData", true, "use a USB device"),
        HID_OPEN_STREAM("hid.openStream", true, "use a USB device"),
        HID_CLOSE_STREAM("hid.closeStream", false, "use a USB device"),
        HID_RELEASE_DEVICE("hid.releaseDevice", false, "release a USB device"),

        FILE_LIST("file.list", true, "view the filesystem"),
        FILE_START_LISTENING("file.startListening", true, "listen for filesystem events"),
        FILE_STOP_LISTENING("file.stopListening", false),
        FILE_READ("file.read", true, "read the content of a file"),
        FILE_WRITE("file.write", true, "write to a file"),
        FILE_REMOVE("file.remove", true, "delete a file"),

        NETWORKING_DEVICE("networking.device", true),
        NETWORKING_DEVICES("networking.devices", true),
        NETWORKING_DEVICE_LEGACY("websocket.getNetworkInfo", true),
        GET_VERSION("getVersion", false),

        INVALID("", false);


        private String callName;
        private String dialogPrompt;
        private boolean dialogShown;

        Method(String callName, boolean dialogShown) {
            this(callName, dialogShown, "access local resources");
        }

        Method(String callName, boolean dialogShown, String dialogPrompt) {
            this.callName = callName;

            this.dialogShown = dialogShown;
            this.dialogPrompt = dialogPrompt;
        }

        public boolean isDialogShown() {
            return dialogShown;
        }

        public String getDialogPrompt() {
            return dialogPrompt;
        }

        public static Method findFromCall(String call) {
            for(Method m : Method.values()) {
                if (m.callName.equals(call)) {
                    return m;
                }
            }

            return INVALID;
        }
    }


    @OnWebSocketConnect
    public void onConnect(Session session) {
        log.info("Connection opened from {} on socket port {}", session.getRemoteAddress(), session.getLocalAddress().getPort());
        trayManager.displayInfoMessage("Client connected");

        //new connections are unknown until they send a proper certificate
        openConnections.put(session.getRemoteAddress().getPort(), new SocketConnection(Certificate.UNKNOWN));
    }

    @OnWebSocketClose
    public void onClose(Session session, int closeCode, String reason) {
        log.info("Connection closed: {} - {}", closeCode, reason);
        trayManager.displayInfoMessage("Client disconnected");

        Integer port = session.getRemoteAddress().getPort();
        SocketConnection closed = openConnections.remove(port);
        if (closed != null) {
            try {
                closed.disconnect();
            }
            catch(Exception e) {
                log.error("Failed to close communication channel", e);
            }
        }
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        log.error("Connection error", error);
        trayManager.displayErrorMessage(error.getMessage());
    }

    @OnWebSocketMessage
    public void onMessage(Session session, Reader reader) throws IOException {
        String message = IOUtils.toString(reader);

        if (message == null || message.isEmpty()) {
            sendError(session, null, "Message is empty");
            return;
        }
        if (Constants.PROBE_REQUEST.equals(message)) {
            try { session.getRemote().sendString(Constants.PROBE_RESPONSE); } catch(Exception ignore) {}
            log.warn("Second instance of {} likely detected, asking it to close", Constants.ABOUT_TITLE);
            return;
        }
        if ("ping".equals(message)) { return; } //keep-alive call / no need to process

        String UID = null;
        try {
            log.debug("Message: {}", message);
            JSONObject json = new JSONObject(message);
            UID = json.optString("uid");

            Integer connectionPort = session.getRemoteAddress().getPort();
            SocketConnection connection = openConnections.get(connectionPort);
            RequestState request = new RequestState(connection.getCertificate(), json);

            //if sent a certificate use that instead for this connection
            if (json.has("certificate")) {
                try {
                    Certificate certificate = new Certificate(json.optString("certificate"));
                    connection.setCertificate(certificate);

                    request.markNewConnection(certificate);

                    log.debug("Received new certificate from connection through {}", connectionPort);
                }
                catch(CertificateParsingException ignore) {
                    request.markNewConnection(Certificate.UNKNOWN);
                }

                if (allowedFromDialog(request, "connect to " + Constants.ABOUT_TITLE,
                                      findDialogPosition(session, json.optJSONObject("position")))) {
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Connection blocked by client");
                    session.disconnect();
                }

                return; //this is a setup call, so no further processing is needed
            }

            //check request signature
            if (request.hasCertificate()) {
                if (json.optLong("timestamp") + Constants.VALID_SIGNING_PERIOD < System.currentTimeMillis()
                        || json.optLong("timestamp") - Constants.VALID_SIGNING_PERIOD > System.currentTimeMillis()) {
                    //bad timestamps use the expired certificate
                    log.warn("Expired signature on request");
                    request.setStatus(RequestState.Validity.EXPIRED);
                } else if (json.isNull("signature") || !validSignature(request.getCertUsed(), json)) {
                    //bad signatures use the unsigned certificate
                    log.warn("Bad signature on request");
                    request.setStatus(RequestState.Validity.UNSIGNED);
                } else {
                    log.trace("Valid signature from {}", request.getCertName());
                    request.setStatus(RequestState.Validity.TRUSTED);
                }
            }

            processMessage(session, json, connection, request);
        }
        catch(JSONException e) {
            log.error("Bad JSON: {}", e.getMessage());
            sendError(session, UID, e);
        }
        catch(InvalidPathException | FileSystemException e) {
            log.error("FileIO exception occurred", e);
            sendError(session, UID, String.format("FileIO exception occurred: %s: %s", e.getClass().getSimpleName(), e.getMessage()));
        }
        catch(Exception e) {
            log.error("Problem processing message", e);
            sendError(session, UID, e);
        }
    }

    private boolean validSignature(Certificate certificate, JSONObject message) throws JSONException {
        JSONObject copy = new JSONObject(message, new String[] {"call", "params", "timestamp"});
        String signature = message.optString("signature");

        return certificate.isSignatureValid(signature, copy.toString().replaceAll("\\\\/", "/"));
    }

    /**
     * Determine which method was called from web API
     *
     * @param session WebSocket session
     * @param json    JSON received from web API
     */
    private void processMessage(Session session, JSONObject json, SocketConnection connection, RequestState request) throws JSONException, SerialPortException, DeviceException, IOException, ListenerNotFoundException {
        String UID = json.optString("uid");
        Method call = Method.findFromCall(json.optString("call"));
        JSONObject params = json.optJSONObject("params");
        if (params == null) { params = new JSONObject(); }

        if (call == Method.INVALID && (UID == null || UID.isEmpty())) {
            //incorrect message format, likely incompatible qz version
            session.close(4003, "Connected to incompatible " + Constants.ABOUT_TITLE + " version");
            return;
        }

        String prompt = call.getDialogPrompt();
        if (call == Method.PRINT) {
            //special formatting for print dialogs
            JSONObject pr = params.optJSONObject("printer");
            if (pr != null) {
                prompt = String.format(prompt, pr.optString("name", pr.optString("file", pr.optString("host", "an undefined location"))));
            } else {
                sendError(session, UID, "A printer must be specified before printing");
                return;
            }
        }

        if (call.isDialogShown()
                && !allowedFromDialog(request, prompt, findDialogPosition(session, json.optJSONObject("position")))) {
            sendError(session, UID, "Request blocked");
            return;
        }

        // used in usb calls
        DeviceOptions dOpts = new DeviceOptions(params, DeviceOptions.DeviceMode.parse(call.callName));


        //call appropriate methods
        switch(call) {
            case PRINTERS_GET_DEFAULT:
                sendResult(session, UID, PrintServiceLookup.lookupDefaultPrintService() == null? null:
                        PrintServiceLookup.lookupDefaultPrintService().getName());
                break;
            case PRINTERS_FIND:
                if (params.has("query")) {
                    String name = PrintServiceMatcher.findPrinterName(params.getString("query"));
                    if (name != null) {
                        sendResult(session, UID, name);
                    } else {
                        sendError(session, UID, "Specified printer could not be found.");
                    }
                } else {
                    JSONArray services = PrintServiceMatcher.getPrintersJSON();
                    JSONArray names = new JSONArray();
                    for(int i = 0; i < services.length(); i++) {
                        names.put(services.getJSONObject(i).getString("name"));
                    }

                    sendResult(session, UID, names);
                }
                break;
            case PRINTERS_DETAIL:
                sendResult(session, UID, PrintServiceMatcher.getPrintersJSON());
                break;
            case PRINTERS_START_LISTENING:
                if (!connection.hasStatusListener()) {
                    connection.startStatusListener(new StatusSession(session));
                }
                StatusMonitor.startListening(connection, params.getJSONArray("printerNames"));
                sendResult(session, UID, null);
                break;
            case PRINTERS_GET_STATUS:
                if (connection.hasStatusListener()) {
                    StatusMonitor.sendStatuses(connection);
                } else {
                    sendError(session, UID, "No printer listeners started for this client.");
                }
                sendResult(session, UID, null);
                break;
            case PRINTERS_STOP_LISTENING:
                if (connection.hasStatusListener()) {
                    connection.stopStatusListener();
                }
                sendResult(session, UID, null);
                break;
            case PRINT:
                PrintingUtilities.processPrintRequest(session, UID, params);
                break;

            case SERIAL_FIND_PORTS:
                sendResult(session, UID, SerialUtilities.getSerialPortsJSON());
                break;
            case SERIAL_OPEN_PORT:
                SerialUtilities.setupSerialPort(session, UID, connection, params);
                break;
            case SERIAL_SEND_DATA: {
                SerialOptions opts = null;
                //properties param is deprecated legacy here and will be overridden by options if provided
                if (!params.isNull("properties")) {
                    opts = new SerialOptions(params.optJSONObject("properties"), false);
                }
                if (!params.isNull("options")) {
                    opts = new SerialOptions(params.optJSONObject("options"), false);
                }

                SerialIO serial = connection.getSerialPort(params.optString("port"));
                if (serial != null) {
                    String serialData;
                    JSONObject data = params.optJSONObject("data");
                    if (data != null) {
                        serialData = data.getString("data");
                    } else {
                        serialData = params.optString("data");
                    }

                    serial.sendData(serialData, opts, SerialUtilities.getDataTypeJSON(data));
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("Serial port [%s] must be opened first.", params.optString("port")));
                }
                break;
            }
            case SERIAL_CLOSE_PORT: {
                SerialIO serial = connection.getSerialPort(params.optString("port"));
                if (serial != null) {
                    serial.close();
                    connection.removeSerialPort(params.optString("port"));
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("Serial port [%s] is not open.", params.optString("port")));
                }
                break;
            }

            case USB_LIST_DEVICES:
                sendResult(session, UID, UsbUtilities.getUsbDevicesJSON(params.getBoolean("includeHubs")));
                break;
            case USB_LIST_INTERFACES:
                sendResult(session, UID, UsbUtilities.getDeviceInterfacesJSON(dOpts));
                break;
            case USB_LIST_ENDPOINTS:
                sendResult(session, UID, UsbUtilities.getInterfaceEndpointsJSON(dOpts));
                break;
            case HID_LIST_DEVICES:
                if (SystemUtilities.isWindows()) {
                    sendResult(session, UID, PJHA_HidUtilities.getHidDevicesJSON());
                } else {
                    sendResult(session, UID, H4J_HidUtilities.getHidDevicesJSON());
                }
                break;
            case HID_START_LISTENING:
                if (!connection.isDeviceListening()) {
                    if (SystemUtilities.isWindows()) {
                        connection.startDeviceListening(new PJHA_HidListener(session));
                    } else {
                        connection.startDeviceListening(new H4J_HidListener(session));
                    }
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Already listening HID device events");
                }
                break;
            case HID_STOP_LISTENING:
                if (connection.isDeviceListening()) {
                    connection.stopDeviceListening();
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Not already listening HID device events");
                }
                break;

            case USB_CLAIM_DEVICE:
            case HID_CLAIM_DEVICE: {
                if (connection.getDevice(dOpts) == null) {
                    DeviceIO device;
                    if (call == Method.USB_CLAIM_DEVICE) {
                        device = new UsbIO(dOpts);
                    } else {
                        if (SystemUtilities.isWindows()) {
                            device = new PJHA_HidIO(dOpts);
                        } else {
                            device = new H4J_HidIO(dOpts);
                        }
                    }

                    if (session.isOpen()) {
                        connection.openDevice(device, dOpts);
                    }

                    if (device.isOpen()) {
                        sendResult(session, UID, null);
                    } else {
                        sendError(session, UID, "Failed to open connection to device");
                    }
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] is already claimed.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_CLAIMED:
            case HID_CLAIMED: {
                sendResult(session, UID, connection.getDevice(dOpts) != null);
                break;
            }
            case USB_SEND_DATA:
            case HID_SEND_DATA: {
                DeviceIO usb = connection.getDevice(dOpts);
                if (usb != null) {
                    usb.sendData(StringUtils.getBytesUtf8(params.optString("data")), dOpts.getEndpoint());
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] must be claimed first.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_READ_DATA:
            case HID_READ_DATA: {
                DeviceIO usb = connection.getDevice(dOpts);
                if (usb != null) {
                    byte[] response = usb.readData(dOpts.getResponseSize(), dOpts.getEndpoint());
                    JSONArray hex = new JSONArray();
                    for(byte b : response) {
                        hex.put(UsbUtil.toHexString(b));
                    }
                    sendResult(session, UID, hex);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] must be claimed first.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_OPEN_STREAM:
            case HID_OPEN_STREAM: {
                StreamEvent.Stream stream = (call == Method.USB_OPEN_STREAM? StreamEvent.Stream.USB:StreamEvent.Stream.HID);
                UsbUtilities.setupUsbStream(session, UID, connection, dOpts, stream);
                break;
            }
            case USB_CLOSE_STREAM:
            case HID_CLOSE_STREAM: {
                DeviceIO usb = connection.getDevice(dOpts);
                if (usb != null && usb.isStreaming()) {
                    usb.setStreaming(false);
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] is not streaming data.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_RELEASE_DEVICE:
            case HID_RELEASE_DEVICE: {
                DeviceIO usb = connection.getDevice(dOpts);
                if (usb != null) {
                    usb.close();
                    connection.removeDevice(dOpts);

                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] is not claimed.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }

            case FILE_START_LISTENING: {
                FileParams fileParams = new FileParams(params);
                Path absPath = FileUtilities.getAbsolutePath(params, request, true);
                FileIO fileIO = new FileIO(session, params, fileParams.getPath(), absPath);

                if (connection.getFileListener(absPath) == null && !fileIO.isWatching()) {
                    connection.addFileListener(absPath, fileIO);

                    FileUtilities.setupListener(fileIO);
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Already listening to path events");
                }

                break;
            }
            case FILE_STOP_LISTENING: {
                if (params.isNull("path")) {
                    connection.removeAllFileListeners();
                    sendResult(session, UID, null);
                } else {
                    Path absPath = FileUtilities.getAbsolutePath(params, request, true);
                    FileIO fileIO = connection.getFileListener(absPath);

                    if (fileIO != null) {
                        fileIO.close();
                        FileWatcher.deregisterWatch(fileIO);
                        connection.removeFileListener(absPath);
                        sendResult(session, UID, null);
                    } else {
                        sendError(session, UID, "Not already listening to path events");
                    }
                }

                break;
            }
            case FILE_LIST: {
                Path absPath = FileUtilities.getAbsolutePath(params, request, true);

                if (Files.exists(absPath)) {
                    if (Files.isDirectory(absPath)) {
                        ArrayList<String> files = new ArrayList<>();
                        Files.list(absPath).forEach(file -> files.add(file.getFileName().toString()));
                        sendResult(session, UID, new JSONArray(files));
                    } else {
                        log.error("Failed to list '{}' (not a directory)", absPath);
                        sendError(session, UID, "Path is not a directory");
                    }
                } else {
                    log.error("Failed to list '{}' (does not exist)", absPath);
                    sendError(session, UID, "Path does not exist");
                }

                break;
            }
            case FILE_READ: {
                Path absPath = FileUtilities.getAbsolutePath(params, request, false);
                if (Files.exists(absPath)) {
                    if (Files.isReadable(absPath)) {
                        sendResult(session, UID, new String(Files.readAllBytes(absPath)));
                    } else {
                        log.error("Failed to read '{}' (not readable)", absPath);
                        sendError(session, UID, "Path is not readable");
                    }
                } else {
                    log.error("Failed to read '{}' (does not exist)", absPath);
                    sendError(session, UID, "Path does not exist");
                }

                break;
            }
            case FILE_WRITE: {
                FileParams fileParams = new FileParams(params);
                Path absPath = FileUtilities.getAbsolutePath(params, request, false);

                Files.createDirectories(absPath.getParent());
                Files.write(absPath, fileParams.getData(), StandardOpenOption.CREATE, fileParams.getAppendMode());
                sendResult(session, UID, null);
                break;
            }
            case FILE_REMOVE: {
                Path absPath = FileUtilities.getAbsolutePath(params, request, false);

                if (Files.exists(absPath)) {
                    Files.delete(absPath);
                    sendResult(session, UID, null);
                } else {
                    log.error("Failed to remove '{}' (does not exist)", absPath);
                    sendError(session, UID, "Path does not exist");
                }

                break;
            }
            case NETWORKING_DEVICE_LEGACY:
                JSONObject networkDevice = NetworkUtilities.getDeviceJSON(params.optString("hostname", "google.com"), params.optInt("port", 443));
                JSONObject legacyDevice = new JSONObject();
                legacyDevice.put("ipAddress", networkDevice.optString("ip", null));
                legacyDevice.put("macAddress", networkDevice.optString("mac", null));
                sendResult(session, UID, legacyDevice);
                break;
            case NETWORKING_DEVICE:
                sendResult(session, UID, NetworkUtilities.getDeviceJSON(params.optString("hostname", "google.com"), params.optInt("port", 443)));
                break;
            case NETWORKING_DEVICES:
                sendResult(session, UID, NetworkUtilities.getDevicesJSON(params.optString("hostname", "google.com"), params.optInt("port", 443)));
                break;
            case GET_VERSION:
                sendResult(session, UID, Constants.VERSION);
                break;

            case INVALID:
            default:
                sendError(session, UID, "Invalid function call: " + json.optString("call", "NONE"));
                break;
        }
    }

    private boolean allowedFromDialog(RequestState request, String prompt, Point position) {
        //If cert can be resolved before the lock, do so and return
        if (request.hasBlockedCert()) {
            return false;
        }
        if (request.hasSavedCert()) {
            return true;
        }

        //wait until previous prompts are closed
        try {
            dialogAvailable.acquire();
        }
        catch(InterruptedException e) {
            log.warn("Failed to acquire dialog", e);
            return false;
        }

        //prompt user for access
        boolean allowed = trayManager.showGatewayDialog(request, prompt, position);

        dialogAvailable.release();

        return allowed;
    }

    private Point findDialogPosition(Session session, JSONObject positionData) {
        Point pos = new Point(0, 0);
        if (session.getRemoteAddress().getAddress().isLoopbackAddress() && positionData != null) {
            pos.move(positionData.optInt("x"), positionData.optInt("y"));
        }

        return pos;
    }


    /**
     * Send JSON reply to web API for call {@code messageUID}
     *
     * @param session     WebSocket session
     * @param messageUID  ID of call from web API
     * @param returnValue Return value of method call, can be {@code null}
     */
    public static void sendResult(Session session, String messageUID, Object returnValue) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("uid", messageUID);
            reply.put("result", returnValue);
            send(session, reply);
        }
        catch(JSONException e) {
            log.error("Send result failed", e);
        }
    }

    /**
     * Send JSON error reply to web API for call {@code messageUID}
     *
     * @param session    WebSocket session
     * @param messageUID ID of call from web API
     * @param ex         Exception to get error message from
     */
    public static void sendError(Session session, String messageUID, Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.equals("")) {
            message = ex.getClass().getSimpleName();
        }

        sendError(session, messageUID, message);
    }

    /**
     * Send JSON error reply to web API for call {@code messageUID}
     *
     * @param session    WebSocket session
     * @param messageUID ID of call from web API
     * @param errorMsg   Error from method call
     */
    public static void sendError(Session session, String messageUID, String errorMsg) {
        try {
            JSONObject reply = new JSONObject();
            reply.putOpt("uid", messageUID);
            reply.put("error", errorMsg);
            send(session, reply);
        }
        catch(JSONException e) {
            log.error("Send error failed", e);
        }
    }

    /**
     * Send JSON data to web API, to be retrieved by callbacks.
     * Used for data sent apart from API calls, since UID's correspond to a single response.
     *
     * @param session WebSocket session
     * @param event   StreamEvent with data to send down to web API
     */
    public static void sendStream(Session session, StreamEvent event) {
        try {
            JSONObject stream = new JSONObject();
            stream.put("type", event.getStreamType());
            stream.put("event", event.toJSON());
            send(session, stream);
        }
        catch(JSONException e) {
            log.error("Send stream failed", e);
        }
    }

    /**
     * Raw send method for replies
     *
     * @param session WebSocket session
     * @param reply   JSON Object of reply to web API
     */
    private static synchronized void send(Session session, JSONObject reply) throws WebSocketException {
        try {
            session.getRemote().sendString(reply.toString());
        }
        catch(IOException e) {
            log.error("Could not send message", e);
        }
    }

}
