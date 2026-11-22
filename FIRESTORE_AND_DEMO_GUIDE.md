# SEHA – setup & demo guide (new ambulance system)

## 1. One-time Firebase setup (5 minutes)
1. Firebase Console -> Authentication -> Sign-in method -> enable **Email/Password** (already) and **Anonymous**
   (anonymous is used for the guardian tracking screen and the emergency face scan when nobody is logged in).
2. Firestore Database -> Rules -> paste `firestore.rules` -> Publish.
   (If you are still using "test mode" rules they EXPIRE after 30 days and the app will suddenly stop working - replace them now.)
3. Nothing to create manually: collections `ambulances`, `hospitals`, `requests` are created by the app.

## 2. Face server
* Run `DeepFaceServer/app.py` on the laptop; phone + laptop on the same Wi-Fi/hotspot.
* In the app open Face Scan and **long-press the status text** -> enter `http://<laptop-ip>:5000` (no rebuild needed).

## 2b. IMPORTANT - re-publish `firestore.rules`
It now also covers `Patients/{id}/records` (medical records added by nurses).

## 3. Demo accounts (create via Sign up)
Create them in THIS order (a nurse needs a registered hospital):
1. **Hospital** - pick the hospital from the combo box (government hospitals of Sri Lanka) + password. Its map position comes from `res/values/hospitals.xml` (verified ones) or is found in OpenStreetMap automatically; if it is wrong use **Choose my hospital from the map list** on the hospital dashboard.
2. **Nurse** - NIC + the same hospital + phone + password. (One nurse per hospital; the nurse's phone gets the ambulance SMS.)
3. **Ambulance** - vehicle number + **the hospital it belongs to (combo box)** + password. Each hospital's dashboard shows only its own ambulances.
4. **Patient** - name, email, phone, relative phone, password.

| Role | Login with |
|---|---|
| Patient | email + password |
| Nurse | NIC + password |
| Ambulance | vehicle number + password |
| Hospital | hospital (combo box) + password |
Register the patient's face in Edit Profile so the nurse can scan it.

## 4. Demo script
1. **Ambulance phone**: login -> switch **Online** (allow location).
2. **Patient phone**: Home -> Ambulance -> **Request nearest ambulance** (shows Patient ID).
3. Ambulance gets the request + sound -> **Accept**. Patient sees the ambulance moving, road route, km, ETA, **Call ambulance**.
4. Ambulance: **Inform guardian** -> Patient ID -> SMS. Guardian: login screen -> **Guardian? Track a patient** -> ID -> live map.
5. Ambulance reaches the patient -> **Patient picked up -> go to nearest hospital** -> list of hospitals, nearest first -> select one.
   * Map shows **START** (patient pickup) and **END** (hospital), the whole trip route in grey, live route in green, trip distance and ETA.
   * **One emergency SMS goes automatically to the hospital's nurse AND the guardian** (Patient ID, destination hospital, ambulance number + phone). Only the ambulance sends it. "Resend SMS" button if needed.
6. **Nurse phone**: the ambulance appears under "Ambulances coming to my hospital" (with a sound), or type the vehicle number from the SMS in **Track an ambulance by vehicle number** -> live map. **Patient records** button shows the history.
7. Ambulance arrives -> only the **driver** can press **Arrived at hospital - Complete trip**.
8. **Nurse dashboard -> Find patient (Patient ID or name) -> Open** (or Scan patient face) -> details with red "DIABETIC" alert -> **Medical history & records**:
   past hospital records, X-ray / scan images, current medicines, pharmacy orders -> **Add record / update illness** (note + camera/gallery image).
   Filter chips: All / X-ray / Blood report / History / Scans / Medicines. Every new record shows **"Added by nurse ID ..."** and cannot be edited or deleted (enforced by firestore.rules).
   Scanning a face also has **Send nearest ambulance** for an unconscious patient.
9. **Hospital account**: tabs **Ambulances** (online/ready/on trip, tap = call driver), **Active trips**, **History**; nurse info; Manage fleet.

## 5. Things to know (be ready for questions)
* Route/ETA uses the free OSRM public server; if there is no internet it falls back to a straight-line estimate automatically.
* Keep the ambulance screen open (screen stays on automatically). A background service can be added later.
* An ambulance not sending location for 2 minutes is treated as offline.
* If an ambulance declines or doesn't answer in 30 s the request automatically goes to the next nearest one.

## 6. SOS + guardian GPS tracking (new)
**IMPORTANT - re-publish `firestore.rules`** (new collections `sos_alerts` and `liveLocations`).

### Patient: SOS button
* Home -> big red **SOS EMERGENCY** card (also in the side menu: *SOS Alert*).
* 5 second countdown (CANCEL = accidental tap) -> GPS -> **nearest hospital registered in SEHA** -> alert document with the patient's
  condition, blood group, allergies, **Patient ID**, phone and GPS position.
* The hospital dashboard **and the nurse dashboard** get a pop-up with an alarm sound -> **Track patient** opens the live map
  (road route, distance, ETA, call patient, patient records, *Acknowledge* -> *Mark resolved*).
  The hospital dashboard also has a new **SOS** tab listing every alert.
* An SMS (Patient ID + Google Maps link) goes to the relative phone and the hospital nurse (silent if SMS permission is granted, otherwise the
  **Send SMS** button opens the messaging app). **Call 1990** and **Request nearest ambulance** buttons are on the SOS screen.
* While the SOS is active the patient's GPS is published live. "I'm safe - cancel SOS" ends it.

### Guardian: Track patient screen
1. Enter the 6-digit Patient ID -> **Track ambulance** or **Track patient (GPS)**.
2. **Security check**: the patient's *username* (the name used at sign-up) must match the Patient ID. 3 wrong tries = locked for 60 s.
3. Only then the map opens: patient's live GPS (or the ambulance trip). During an SOS it also shows "SOS active - hospital notified".
* The patient's location is visible only while the patient is sharing: SOS active, or the **Share my live location** switch on the Home screen is ON.
* Location updates work while the app is open (no background service) - the map shows "updated X s ago" and a warning when the position is old.
