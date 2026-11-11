/*
  Home Wall LED controller (ESP32 + WS2811), LED protocol v1.

  Setup:
    1. In Boards Manager, install "esp32 by Espressif Systems" (once).
    2. In Library Manager, install "FastLED" (once).
    3. Paste this file into a new sketch, select ESP32 Dev Module and the port, and click Upload.

  Everything is already set for the wall; nothing needs editing. Serial monitor: 115200 baud.

  The tablet app connects over Bluetooth LE and sends a palette of colors and "frames" (which LED
  shows which palette color). The protocol is described in docs/NORTH_STAR.md, section 11. It is a
  contract with the app: don't change one side without the other.

  Test without the app: with nRF Connect, connect to "HomeWall" and write the hex test messages
  from PROGRESS.md to the LED_CTRL characteristic (...0002).
*/

// The libraries come first: FastLED's headers use names like DATA_PIN internally, so these
// settings must be defined after it's included.
#include <FastLED.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

// ---- Home Wall settings ----
#define DATA_PIN            16      // GPIO connected to the LED data line
#define LED_COUNT           550
#define CHIPSET             WS2811
#define COLOR_ORDER         RGB
#define DEVICE_NAME         "HomeWall"
#define MAX_MILLIAMPS       7000    // power cap: ~85% of the 24 V, 8.33 A supply
#define MA_PER_PIXEL_WHITE  15      // estimated draw of one 24 V pixel at full white

// ---- Protocol v1 (NORTH_STAR.md §11) ----
#define SERVICE_UUID   "5b1e0001-7c2f-4a8e-9d3b-2f6a1c4e8b70"
#define LED_CTRL_UUID  "5b1e0002-7c2f-4a8e-9d3b-2f6a1c4e8b70"
#define INFO_UUID      "5b1e0003-7c2f-4a8e-9d3b-2f6a1c4e8b70"

const uint8_t PROTOCOL_VERSION = 1;
const uint8_t PALETTE_SLOTS = 16;
const uint16_t PREFERRED_MTU = 517;

const uint8_t OP_SET_PALETTE = 0x01;
const uint8_t OP_FRAME = 0x02;
const uint8_t FLAG_BEGIN = 0x01;   // clear the back buffer first
const uint8_t FLAG_COMMIT = 0x02;  // show the back buffer

// ---- State, shared by the Bluetooth callback and loop() ----
uint8_t backSlots[LED_COUNT];   // frame being built (a big frame arrives in several packets)
uint8_t frontSlots[LED_COUNT];  // last committed frame: what the wall shows
CRGB palette[PALETTE_SLOTS];    // slot 0 is always black
bool needsRender = false;
SemaphoreHandle_t stateLock;    // guards the four above

CRGB leds[LED_COUNT];

BLEServer* server = nullptr;
volatile bool connected = false;
volatile bool restartAdvertising = false;
volatile uint32_t disconnectedAtMs = 0;

// ---- Message handling (runs in the Bluetooth task: no FastLED.show() here) ----

void handleSetPalette(uint8_t seq, const uint8_t* body, size_t length) {
  if (length % 4 != 0) {
    Serial.printf("Ignored SET_PALETTE seq=%u: body is %u bytes, not a multiple of 4\n", seq, (unsigned)length);
    return;
  }
  size_t count = length / 4;
  xSemaphoreTake(stateLock, portMAX_DELAY);
  for (size_t i = 0; i < count; i++) {
    const uint8_t* entry = body + i * 4;
    uint8_t slot = entry[0];
    if (slot == 0 || slot >= PALETTE_SLOTS) continue;  // slot 0 stays black
    palette[slot] = CRGB(entry[1], entry[2], entry[3]);
  }
  needsRender = true;  // re-render the current frame in the new colors
  xSemaphoreGive(stateLock);
  Serial.printf("SET_PALETTE seq=%u slots=%u\n", seq, (unsigned)count);
}

void handleFrame(uint8_t seq, const uint8_t* body, size_t length) {
  if (length < 1 || (length - 1) % 3 != 0) {
    Serial.printf("Ignored FRAME seq=%u: body is %u bytes, expected 1 + 3n\n", seq, (unsigned)length);
    return;
  }
  uint8_t flags = body[0];
  size_t count = (length - 1) / 3;
  xSemaphoreTake(stateLock, portMAX_DELAY);
  if (flags & FLAG_BEGIN) {
    memset(backSlots, 0, sizeof(backSlots));  // also drops any half-received frame
  }
  for (size_t i = 0; i < count; i++) {
    const uint8_t* entry = body + 1 + i * 3;
    uint16_t led = entry[0] | (entry[1] << 8);
    if (led < LED_COUNT) backSlots[led] = entry[2];  // out-of-range LEDs are ignored
  }
  if (flags & FLAG_COMMIT) {
    memcpy(frontSlots, backSlots, sizeof(frontSlots));
    needsRender = true;
  }
  xSemaphoreGive(stateLock);
  Serial.printf("FRAME seq=%u flags=%02X entries=%u\n", seq, flags, (unsigned)count);
}

void handleMessage(const uint8_t* data, size_t length) {
  if (data == nullptr || length < 2) {
    Serial.printf("Ignored message: %u bytes is too short\n", (unsigned)length);
    return;
  }
  uint8_t opcode = data[0];
  uint8_t seq = data[1];
  switch (opcode) {
    case OP_SET_PALETTE: handleSetPalette(seq, data + 2, length - 2); break;
    case OP_FRAME:       handleFrame(seq, data + 2, length - 2); break;
    default:
      Serial.printf("Ignored message seq=%u: unknown opcode %02X\n", seq, opcode);
  }
}

// ---- Bluetooth ----

class LedCtrlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    // getData()/getLength() work the same on the 2.x and 3.x cores (getValue() doesn't).
    handleMessage(characteristic->getData(), characteristic->getLength());
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    connected = true;
    Serial.println("Connected");
  }
  void onDisconnect(BLEServer* s) override {
    // The last frame stays lit, so the route stays up if the tablet sleeps.
    connected = false;
    disconnectedAtMs = millis();
    restartAdvertising = true;
    Serial.println("Disconnected");
  }
};

void startBluetooth() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(PREFERRED_MTU);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService* service = server->createService(SERVICE_UUID);

  BLECharacteristic* ledCtrl = service->createCharacteristic(
      LED_CTRL_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  ledCtrl->setCallbacks(new LedCtrlCallbacks());

  // INFO: protocol version, LED count (uint16 little-endian), palette slots.
  static uint8_t info[4] = {PROTOCOL_VERSION, LED_COUNT & 0xFF, (LED_COUNT >> 8) & 0xFF, PALETTE_SLOTS};
  BLECharacteristic* infoChar = service->createCharacteristic(INFO_UUID, BLECharacteristic::PROPERTY_READ);
  infoChar->setValue(info, sizeof(info));

  service->start();

  // The service UUID goes in the advertising packet (the app scans for it) and the name in the
  // scan response. Together they'd be over the 31-byte limit for one packet.
  BLEAdvertisementData advertData;
  advertData.setFlags(0x06);  // general discoverable, no classic Bluetooth
  advertData.setCompleteServices(BLEUUID(SERVICE_UUID));
  BLEAdvertisementData scanResponse;
  scanResponse.setName(DEVICE_NAME);

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->setAdvertisementData(advertData);
  advertising->setScanResponseData(scanResponse);
  BLEDevice::startAdvertising();
  Serial.printf("Advertising as %s (preferred MTU %u)\n", DEVICE_NAME, PREFERRED_MTU);
}

// ---- Setup & loop ----

void setup() {
  Serial.setTxBufferSize(1024);  // so logging from the Bluetooth callback doesn't wait on the UART
  Serial.begin(115200);
  Serial.println();
  Serial.println("Home Wall LED controller, protocol v1");

  stateLock = xSemaphoreCreateMutex();
  memset(backSlots, 0, sizeof(backSlots));
  memset(frontSlots, 0, sizeof(frontSlots));
  for (uint8_t i = 0; i < PALETTE_SLOTS; i++) palette[i] = CRGB::Black;
  // The app sends its palette on every connect. These defaults (§9.3, before brightness) only
  // matter if a frame arrives first, e.g. when testing by hand.
  palette[1] = CRGB(0, 255, 0);      // start
  palette[2] = CRGB(0, 0, 255);      // middle
  palette[3] = CRGB(150, 0, 255);    // finish
  palette[4] = CRGB(255, 255, 255);  // setup cursor
  palette[5] = CRGB(64, 64, 64);     // setup "all holds"

  FastLED.addLeds<CHIPSET, DATA_PIN, COLOR_ORDER>(leds, LED_COUNT);
  FastLED.setBrightness(255);  // brightness is applied in the app's palette
  FastLED.clear(true);         // boot with every LED off

  startBluetooth();
}

void logMtuChanges() {
  static uint16_t loggedMtu = 0;
  static uint32_t lastCheckMs = 0;
  if (!connected) {
    loggedMtu = 0;
    return;
  }
  if (millis() - lastCheckMs < 250) return;
  lastCheckMs = millis();
  uint16_t mtu = server->getPeerMTU(server->getConnId());
  if (mtu != 0 && mtu != loggedMtu) {
    loggedMtu = mtu;
    Serial.printf("MTU %u (max packet %u bytes)\n", mtu, (unsigned)(mtu - 3 < 512 ? mtu - 3 : 512));
  }
}

void render() {
  static uint8_t slots[LED_COUNT];
  static CRGB colors[PALETTE_SLOTS];

  xSemaphoreTake(stateLock, portMAX_DELAY);
  bool dirty = needsRender;
  if (dirty) {
    memcpy(slots, frontSlots, sizeof(slots));
    memcpy(colors, palette, sizeof(colors));
    needsRender = false;
  }
  xSemaphoreGive(stateLock);
  if (!dirty) return;

  uint32_t channelSum = 0;
  for (uint16_t i = 0; i < LED_COUNT; i++) {
    uint8_t slot = slots[i];
    leds[i] = slot < PALETTE_SLOTS ? colors[slot] : CRGB::Black;  // unknown slots render as off
    channelSum += leds[i].r + leds[i].g + leds[i].b;
  }

  // Power cap: a full-white pixel (r + g + b = 765) draws about MA_PER_PIXEL_WHITE.
  uint32_t estimateMa = channelSum * MA_PER_PIXEL_WHITE / 765;
  if (estimateMa > MAX_MILLIAMPS) {
    uint8_t scale = (uint32_t)MAX_MILLIAMPS * 256 / estimateMa;
    nscale8(leds, LED_COUNT, scale);
    Serial.printf("Power cap: estimated %u mA, scaled to %u/256\n", (unsigned)estimateMa, scale);
  }

  FastLED.show();
}

void loop() {
  if (restartAdvertising && millis() - disconnectedAtMs > 200) {
    restartAdvertising = false;
    BLEDevice::startAdvertising();
    Serial.println("Advertising again");
  }
  logMtuChanges();
  render();
  delay(1);
}
