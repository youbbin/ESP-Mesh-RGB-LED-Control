#include "painlessMesh.h"
#include <Adafruit_NeoPixel.h>
#define PIN 25
#define NUMPIXELS 8
#define BRIGHTNESS 100
Adafruit_NeoPixel strip=Adafruit_NeoPixel(NUMPIXELS, PIN, NEO_GRB+NEO_KHZ800);

#define   MESH_PREFIX     "Mesh_username"
#define   MESH_PASSWORD   "mesh_password"
#define   MESH_PORT       5555

Scheduler userScheduler; 
painlessMesh  mesh;

void sendmsg() ;

Task taskSendmsg( TASK_SECOND * 1 , TASK_FOREVER, &sendmsg );

void sendmsg() {
  String msg = "Hi, I'm Node1(";
  msg += mesh.getNodeId();
  msg += ")";
  mesh.sendBroadcast( msg );
  taskSendmsg.setInterval( random( TASK_SECOND * 1, TASK_SECOND * 5 ));
}


void receivedCallback( uint32_t from, String &msg ) {
  Serial.printf("Received from %u msg = %s\n", from, msg.c_str());
  
  int index_power = msg.indexOf("/");
  int index_r = msg.indexOf("/",index_power+1);
  int index_g = msg.indexOf("/",index_r+1);
  int len = msg.length();
  
  String str_power = msg.substring(0,index_power);
  String str_r = msg.substring(index_power+1, index_r);
  String str_g = msg.substring(index_r+1, index_g);
  String str_b = msg.substring(index_g+1,len);
  
  int r = str_r.toInt();
  int g = str_g.toInt();
  int b = str_b.toInt();
  
  if(str_power.equals("on")){
    strip.begin();
    strip.show();
    strip.clear();
    for(int i=0;i<8;i++){
      strip.setPixelColor(i,r,g,b);
    }
    strip.show();
  }
  if(str_power.equals("off")){
    strip.begin();
    strip.show();
    strip.clear();
 for(int i=0;i<8;i++){
      strip.setPixelColor(i,0,0,0);
    }
    strip.show();
  }
}

void newConnectionCallback(uint32_t nodeId) {
    Serial.printf("New Connection, nodeId = %u\n", nodeId);
}

void changedConnectionCallback() {
  Serial.printf("Changed connections\n");
}

void nodeTimeAdjustedCallback(int32_t offset) {
    Serial.printf("Adjusted time %u. Offset = %d\n", mesh.getNodeTime(),offset);
}

void setup() {
  Serial.begin(115200);
  mesh.setDebugMsgTypes( ERROR | STARTUP );  

  mesh.init( MESH_PREFIX, MESH_PASSWORD, &userScheduler, MESH_PORT );
  mesh.onReceive(&receivedCallback);
  mesh.onNewConnection(&newConnectionCallback);
  mesh.onChangedConnections(&changedConnectionCallback);
  mesh.onNodeTimeAdjusted(&nodeTimeAdjustedCallback);

  userScheduler.addTask( taskSendmsg );
  taskSendmsg.enable();

  strip.setBrightness(BRIGHTNESS);
  strip.begin();
  strip.show();
  strip.clear();
}

void loop() {
  mesh.update();
}
