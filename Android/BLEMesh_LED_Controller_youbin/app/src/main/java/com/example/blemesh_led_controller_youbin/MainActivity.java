package com.example.blemesh_led_controller_youbin;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;


import com.larswerkman.holocolorpicker.ColorPicker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String DBG_TAG = "MeshActivity";
    private static boolean tryToConnect = false;
    private static boolean isConnected = false;
    private WifiManager wifiManager;
    private static boolean userDisConRequest = false;

    /**
     * WiFi AP to which device was connected before connecting to mesh
     */
    private String oldAPName = "";
    private static String meshIP;

    private String meshName;
    private String meshPw;
    private static int meshPort;


    static long myNodeId = 0;
    static long apNodeId = 0;

    static BufferedWriter out = null;

    private long filterId = 0;

    TextView textViewMeshName;
    TextView textViewMyId;
    ListView listView;

    static int r = 255;
    static int g = 255;
    static int b = 255;
    String message = "";

    Boolean on[] = new Boolean[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("BLE MESH LED CONTROLLER");

        textViewMeshName = (TextView) findViewById(R.id.textViewMeshName);
        textViewMyId = (TextView) findViewById(R.id.textViewMyId);
        listView = (ListView) findViewById(R.id.listView);


        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            boolean timeForNodeReq = true;

            @Override
            public void run() {
                if (MeshCommunicator.isConnected()) {
                    if (timeForNodeReq) {
                        MeshHandler.sendNodeSyncRequest();
                        timeForNodeReq = false;
                    } else {
                        MeshHandler.sendTimeSyncRequest();
                        timeForNodeReq = true;
                    }
                }
                handler.postDelayed(this, 10000);
            }
        }, 10000);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        handleConnection();

        for (int i = 0; i < on.length; i++) {
            on[i] = false;
        }
    }
    @SuppressLint({"DefaultLocale", "ClickableViewAccessibility"})
    @Override
    protected void onResume () {
            SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

            meshName = mPrefs.getString("pm_ssid", "Mesh_username");
            meshPw = mPrefs.getString("pm_pw", "mesh_password");
            meshPort = Integer.valueOf(mPrefs.getString("pm_port", "5555"));

            // Ask for permissions if necessary
            ArrayList<String> arrPerm = new ArrayList<>();
            // On newer Android versions it is required to get the permission of the user to
            // get the location of the device. This is necessary to do a WiFi scan for APs.
            // I am not sure at all what that has to be with
            // the permission to use Bluetooth or BLE, but you need to get it anyway
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                arrPerm.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                arrPerm.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                arrPerm.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }

            if (!arrPerm.isEmpty()) {
                String[] permissions = new String[arrPerm.size()];
                permissions = arrPerm.toArray(permissions);
                ActivityCompat.requestPermissions(this, permissions, 0);
            }

            // Enable access to connectivity
            // ThreadPolicy to get permission to access connectivity
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            // Get the wifi manager
            //wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);


            // Register Mesh events

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(MeshCommunicator.MESH_DATA_RECVD);
            intentFilter.addAction(MeshCommunicator.MESH_SOCKET_ERR);
            intentFilter.addAction(MeshCommunicator.MESH_CONNECTED);
            intentFilter.addAction(MeshCommunicator.MESH_NODES);
            intentFilter.addAction(MeshCommunicator.MESH_OTA);
            intentFilter.addAction(MeshCommunicator.MESH_OTA_REQ);
            // Register network change events
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            // Register receiver
            registerReceiver(localBroadcastReceiver, intentFilter);
            //handleConnection();
            //selectNodeForSending();
//        if(MeshCommunicator.isConnected()){
//            selectNodeForSending();
//        }
            super.onResume();
        }

        @Override
        protected void onDestroy () {
            super.onDestroy();
            if (MeshCommunicator.isConnected()) {
                MeshCommunicator.Disconnect();
            }
            // unregister the broadcast receiver
            unregisterReceiver(localBroadcastReceiver);
        }


        private void handleConnection () {
            if (!isConnected) {
                if (tryToConnect) {
                    stopConnection();
                } else {
                    startConnectionRequest();
                }
            } else stopConnection();
        }

        private void startConnectionRequest () {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            tryToConnect = true;
            userDisConRequest = false;


            // Get current active WiFi AP
            oldAPName = "";

            // Get current WiFi connection
            ConnectivityManager connManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connManager != null) {
                NetworkInfo networkInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (networkInfo.isConnected()) {
                    final WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                    if (connectionInfo != null && !connectionInfo.getSSID().isEmpty()) {
                        oldAPName = connectionInfo.getSSID();
                        Log.i(DBG_TAG, "Connection Info :" + connectionInfo.getSSID());
                    }
                }
            } else {
                Log.i(DBG_TAG, "Connectivity Manager is null");
            }

            // Add device AP to network list and enable it
            WifiConfiguration meshAPConfig = new WifiConfiguration();
            meshAPConfig.SSID = "\"" + meshName + "\"";
            meshAPConfig.preSharedKey = "\"" + meshPw + "\"";
            int newId = wifiManager.addNetwork(meshAPConfig);
            if (BuildConfig.DEBUG) Log.i(DBG_TAG, "Result of addNetwork: " + newId);
            wifiManager.disconnect();
            wifiManager.enableNetwork(newId, true);
            wifiManager.reconnect();
        }

        private void stopConnection () {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

            if (MeshCommunicator.isConnected()) {
                MeshCommunicator.Disconnect();
            }
            isConnected = false;
            tryToConnect = false;
            userDisConRequest = true;
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            List<WifiConfiguration> availAPs = wifiManager.getConfiguredNetworks();

            if (oldAPName.isEmpty()) {
                for (int index = 0; index < availAPs.size(); index++) {
                    if (availAPs.get(index).SSID.equalsIgnoreCase("\"" + meshName + "\"")) {
                        wifiManager.disconnect();
                        wifiManager.disableNetwork(availAPs.get(index).networkId);
                        if (BuildConfig.DEBUG)
                            Log.d(DBG_TAG, "Disabled: " + availAPs.get(index).SSID);
                        wifiManager.reconnect();
                        break;
                    }
                }
            } else {
                for (int index = 0; index < availAPs.size(); index++) {
                    if (availAPs.get(index).SSID.equalsIgnoreCase(oldAPName)) {
                        wifiManager.disconnect();
                        wifiManager.enableNetwork(availAPs.get(index).networkId, true);
                        if (BuildConfig.DEBUG)
                            Log.d(DBG_TAG, "Re-enabled: " + availAPs.get(index).SSID);
                        wifiManager.reconnect();
                        break;
                    }
                }
            }
            //stopLogging();
        }

        private final BroadcastReceiver localBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onReceive(Context context, Intent intent) {
                String intentAction = intent.getAction();
                Log.d(DBG_TAG, "Received broadcast: " + intentAction);

                // WiFi events
                if (isConnected) {
                    // Did we loose connection to the mesh network?
                    /* Access to connectivity manager */
                    ConnectivityManager cm1 = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                    /* WiFi connection information  */
                    NetworkInfo wifiOn;
                    if (cm1 != null) {
                        wifiOn = cm1.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                        if (!wifiOn.isConnected()) {
                            isConnected = false;
                            runOnUiThread(() -> stopConnection());
                        }
                    }

                }
                if (tryToConnect && (intentAction != null) && (intentAction.equals(android.net.ConnectivityManager.CONNECTIVITY_ACTION))) {
                    /* Access to connectivity manager */
                    ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                    /* WiFi connection information  */
                    NetworkInfo wifiOn;
                    if (cm != null) {
                        wifiOn = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                        if (wifiOn.isConnected()) {
                            if (tryToConnect) {
                                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                                if (wifiInfo.getSSID().equalsIgnoreCase("\"" + meshName + "\"")) {
                                    Log.d(DBG_TAG, "Connected to Mesh network " + wifiOn.getExtraInfo());
                                    // Get the gateway IP address
                                    DhcpInfo dhcpInfo;
                                    if (wifiManager != null) {
                                        // Create the mesh AP node ID from the AP MAC address
                                        apNodeId = MeshHandler.createMeshID(wifiInfo.getBSSID());

                                        dhcpInfo = wifiManager.getDhcpInfo();
                                        // Get the mesh AP IP
                                        int meshIPasNumber = dhcpInfo.gateway;
                                        meshIP = ((meshIPasNumber & 0xFF) + "." +
                                                ((meshIPasNumber >>>= 8) & 0xFF) + "." +
                                                ((meshIPasNumber >>>= 8) & 0xFF) + "." +
                                                (meshIPasNumber >>> 8 & 0xFF));

                                        // Create our node ID
                                        myNodeId = MeshHandler.createMeshID(MeshHandler.getWifiMACAddress());
                                        Log.d(DBG_TAG, "Mesh IP : " + meshIP);
                                        Log.d(DBG_TAG, "My Node Id : " + myNodeId);
                                        //MeshCommunicator.Connect(meshIP, meshPort, getApplicationContext());
                                        //selectNodeForSending();
                                    } else {
                                        // We are screwed. Tell user about the problem
                                        Log.e(DBG_TAG, "Critical Error -- cannot get WifiManager access");
                                    }
                                    // Rest has to be done on UI thread
                                    runOnUiThread(() -> {
                                        tryToConnect = false;

                                        textViewMyId.setText("My ID : " + myNodeId);
                                        textViewMeshName.setText("Mesh Name : " + meshName);

                                        // Set flag that we are connected
                                        isConnected = true;

                                        // Connected to the Mesh network, start network task now
                                        MeshCommunicator.Connect(meshIP, meshPort, getApplicationContext());
                                    });
                                } else {
                                    List<WifiConfiguration> availAPs = wifiManager.getConfiguredNetworks();

                                    for (int index = 0; index < availAPs.size(); index++) {
                                        if (availAPs.get(index).SSID.equalsIgnoreCase("\"" + meshName + "\"")) {
                                            wifiManager.disconnect();
                                            wifiManager.enableNetwork(availAPs.get(index).networkId, true);
                                            if (BuildConfig.DEBUG)
                                                Log.d(DBG_TAG, "Retry to enable: " + availAPs.get(index).SSID);
                                            wifiManager.reconnect();
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                String dataSet = "";

                // Mesh events
                if (MeshCommunicator.MESH_DATA_RECVD.equals(intentAction)) {
                    String rcvdMsg = intent.getStringExtra("msg");
                    String oldText;
                    try {
                        JSONObject rcvdJSON = new JSONObject(rcvdMsg);
                        int msgType = rcvdJSON.getInt("type");
                        long fromNode = rcvdJSON.getLong("from");
                        switch (msgType) {
                            case 3: // TIME_DELAY
                                dataSet += "Received TIME_DELAY\n";
                                break;
                            case 4: // TIME_SYNC
                                dataSet += "Received TIME_SYNC\n";
                                break;
                            case 5: // NODE_SYNC_REQUEST
                            case 6: // NODE_SYNC_REPY
                                if (msgType != 5) {
                                    dataSet += "Received NODE_SYNC_REPLY\n";
                                } else {
                                    dataSet += "Received NODE_SYNC_REQUEST\n";
                                }
                                // Generate known nodes list
                                final String nodesListString = rcvdMsg;
                                final Handler handler = new Handler();
                                handler.post(() -> MeshHandler.generateNodeList(nodesListString));
                                Log.d(DBG_TAG, "nodesListString : " + MeshHandler.nodesList);
                                if (MeshHandler.nodesList != null) {
                                    selectNodeForSending();
                                }
                                //selectNodeForSending();
                                break;
                            case 7: // CONTROL ==> deprecated
                                dataSet += "Received CONTROL\n";
                                break;
                            case 8: // BROADCAST
                                dataSet += "Broadcast:\n" + rcvdJSON.getString("msg") + "\n";
                                if (filterId != 0) {
                                    if (fromNode != filterId) {
                                        return;
                                    }
                                }
                                oldText = "BC from " + fromNode + "\n\t" + rcvdJSON.getString("msg") + "\n";
                                break;
                            case 9: // SINGLE
                                dataSet += "Single Msg:\n" + rcvdJSON.getString("msg") + "\n";
                                // Check if the message is a OTA req message
                                JSONObject rcvdData = new JSONObject(rcvdJSON.getString("msg"));
                                String dataType = rcvdData.getString("plugin");
                                if ((dataType != null) && dataType.equalsIgnoreCase("ota")) {
                                    dataType = rcvdData.getString("type");
                                    if (dataType != null) {
                                        if (dataType.equalsIgnoreCase("version")) {
                                            // We received a OTA advertisment!
                                            return;
                                        } else if (dataType.equalsIgnoreCase("request")) {
                                            // We received a OTA block request
                                            MeshHandler.sendOtaBlock(fromNode, rcvdData.getLong("partNo"));
                                        }
                                    }
                                }
                                if (filterId != 0) {
                                    if (fromNode != filterId) {
                                        return;
                                    }
                                }
                                oldText = "SM from " + fromNode + "\n\t" + rcvdJSON.getString("msg") + "\n";
                                break;
                        }
                    } catch (JSONException e) {
                        Log.d(DBG_TAG, "Received message is not a JSON Object!");
                        oldText = "E: " + intent.getStringExtra("msg") + "\n";
                        dataSet += "ERROR INVALID DATA:\n" + intent.getStringExtra("msg") + "\n";
                    }
                    if (out != null) {
                        try {
                            out.append(dataSet);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                } else if (MeshCommunicator.MESH_SOCKET_ERR.equals(intentAction)) {
                    if (MeshHandler.nodesList != null) {
                        MeshHandler.nodesList.clear();
                    }
                    if (!userDisConRequest) {
                        MeshCommunicator.Connect(meshIP, meshPort, getApplicationContext());
                    }
                } else if (MeshCommunicator.MESH_CONNECTED.equals(intentAction)) {
                    userDisConRequest = false;
                } else if (MeshCommunicator.MESH_NODES.equals(intentAction)) {
                    String oldText = intent.getStringExtra("msg") + "\n";
                    dataSet += intent.getStringExtra("msg") + "\n";
                    if (out != null) {
                        try {
                            out.append(dataSet);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        };

        private void selectNodeForSending () {
            ArrayList<String> nodesListStr = new ArrayList<>();

            ArrayList<Long> tempNodesList = new ArrayList<>(MeshHandler.nodesList);

            tempNodesList.add(0L);
            Collections.sort(tempNodesList);

            for (int idx = 0; idx < tempNodesList.size(); idx++) {
                nodesListStr.add("LED " + (idx) + " (" + String.valueOf(tempNodesList.get(idx)) + ")");
            }
            nodesListStr.set(0, "All LED");

            //ArrayAdapter<String> nodeListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nodesListStr);
            SwitchListAdapter switchListAdapter = new SwitchListAdapter(this, nodesListStr);
            listView.setAdapter(switchListAdapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
                    AlertDialog.Builder d = new AlertDialog.Builder(MainActivity.this);
                    d.setTitle("LED Control");
                    View view_dialog = View.inflate(MainActivity.this, R.layout.dialog, null);
                    d.setView(view_dialog);

                    ToggleButton toggleButton = (ToggleButton) view_dialog.findViewById(R.id.toggleButton);
                    ColorPicker colorPicker = (ColorPicker) view_dialog.findViewById(R.id.colorPicker);
                    colorPicker.setShowOldCenterColor(false);


                    if(switchListAdapter.getItemId(position)==0){
                        if(on[0]&&on[1]&&on[2]){
                            toggleButton.setChecked(true);
                        }else toggleButton.setChecked(false);
                    }
                    else{
                        if(on[position-1]){
                            toggleButton.setChecked(true);
                        }else{
                            toggleButton.setChecked(false);
                        }
                    }
                    toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {

                            Log.d(">>>","position : "+position);
                            String item = switchListAdapter.getItem(position).toString();
                            Log.d(">>>","item str : "+item);

                            if (isChecked) {
                                if(switchListAdapter.getItemId(position) == 0) {
                                    message = "on/" + r + "/" + g + "/" + b;
                                    MeshHandler.sendNodeMessage(0, message);
                                    for(int i=0;i<on.length;i++) {
                                        on[i] = true;
                                    }
                                }
                                else {
                                    String nodeId_str = item.substring((item.indexOf('(')) + 1, item.indexOf(')'));
                                    Long nodeId_long = Long.parseLong(nodeId_str);
                                    message = "on/" + r + "/" + g + "/" + b;
                                    MeshHandler.sendNodeMessage(nodeId_long, message);
                                    on[position-1]=true;
                                }

                            }else {

                                if(switchListAdapter.getItemId(position) == 0) {
                                    message = "off/" + r + "/" + g + "/" + b;
                                    MeshHandler.sendNodeMessage(0, message);
                                    for(int i = 0;i<on.length;i++) {
                                        on[i] = false;
                                    }
                                }
                                else {
                                    String nodeId_str = item.substring((item.indexOf('(')) + 1, item.indexOf(')'));
                                    Long nodeId_long = Long.parseLong(nodeId_str);
                                    message = "off/" + r + "/" + g + "/" + b;
                                    MeshHandler.sendNodeMessage(nodeId_long, message);
                                    on[position-1]=false;
                                }

                            }

                        }
                    });

                    d.setPositiveButton("확인",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    int color = colorPicker.getColor();
                                    r = Color.red(color);
                                    g = Color.green(color);
                                    b = Color.blue(color);

                                    if (switchListAdapter.getItemId(position) == 0) {
                                        if (on[0]&&on[1]&&on[2]) {
                                            message = "on/" + r + "/" + g + "/" + b;
                                            MeshHandler.sendNodeMessage(0, message);
                                        } else {
                                            message = "off/" + r + "/" + g + "/" + b;
                                            MeshHandler.sendNodeMessage(0, message);
                                        }
                                    } else {
                                        String nodeId_str = switchListAdapter.getItem(position).toString().substring((switchListAdapter.getItem(position).toString().indexOf('(')) + 1, (switchListAdapter.getItem(position)).toString().indexOf(')'));
                                        Long nodeId_long = Long.parseLong(nodeId_str);
                                        if (on[position-1]) {
                                            message = "on/" + r + "/" + g + "/" + b;
                                            MeshHandler.sendNodeMessage(nodeId_long, message);
                                        } else {
                                            message = "off/" + r + "/" + g + "/" + b;
                                            MeshHandler.sendNodeMessage(nodeId_long, message);
                                        }
                                    }
                                }
                            });

                    d.show();
                }
            });
        }
}

