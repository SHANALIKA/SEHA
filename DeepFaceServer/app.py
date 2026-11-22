from flask import Flask, request, jsonify, send_from_directory
from flask_cors import CORS
from deepface import DeepFace
from PIL import Image, ImageOps
import os
import shutil
import tempfile
import threading
import uuid
import glob

app = Flask(__name__)
CORS(app)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "faces")

MODEL = "VGG-Face"
# opencv detector eka weak (kalu/dark photos walin face eka hoyaganna ba).
# Prashna awoth "mtcnn" nathnam "opencv" karanna.
# Slow laptop? run:  set FACE_DETECTOR=ssd   (or opencv)  before  python app.py
DETECTOR = os.environ.get("FACE_DETECTOR", "retinaface")

# A face is accepted when its distance to the closest registered photo is <= THRESHOLD.
# Smaller = stricter (safer), bigger = more matches. 0.68 is the DeepFace default for VGG-Face (cosine).
# Too many "not registered" for registered people?  set FACE_THRESHOLD=0.75   (before python app.py)
# Wrong person recognised?                           set FACE_THRESHOLD=0.55
THRESHOLD = float(os.environ.get("FACE_THRESHOLD", "0.68"))

os.makedirs(DB_PATH, exist_ok=True)


def best_match(result):
    """DeepFace.find returns one table PER FACE found in the photo. Look at ALL of them
    (not only the first one) and return the closest registered photo."""
    best = None
    for item in result or []:
        if hasattr(item, "to_dict"):
            rows = item.to_dict("records")
        elif isinstance(item, list):
            rows = item
        else:
            rows = []
        for r in rows:
            d = r.get("distance")
            if d is None:
                continue
            d = float(d)
            if best is None or d < best["distance"]:
                best = {"identity": r.get("identity"), "distance": d}
    return best


def normalize_image(path, max_side=1000):
    """Phone photo eke EXIF rotation eka hariyata damala, size eka podi karanawa.
    Eken detection ekath fast wenawa, rotation prashnath nathi wenawa."""
    img = Image.open(path)
    img = ImageOps.exif_transpose(img).convert("RGB")
    img.thumbnail((max_side, max_side))
    img.save(path, "JPEG", quality=92)


# ===================== 1. FACE REGISTER =====================
@app.route("/register", methods=["POST"])
def register():
    if "image" not in request.files:
        return jsonify({"status": "error", "message": "No image"}), 400

    person_name = (request.form.get("person_name") or "").strip()
    if not person_name:
        return jsonify({"status": "error", "message": "person_name required"}), 400
    if any(c in person_name for c in '\\/:*?"<>|') or ".." in person_name:
        return jsonify({"status": "error", "message": "Invalid name"}), 400

    temp = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg")
    request.files["image"].save(temp.name)
    temp.close()

    try:
        normalize_image(temp.name)

        # Face ekak nathi photo (dark, face podi) save karanna epa
        try:
            DeepFace.extract_faces(
                img_path=temp.name,
                detector_backend=DETECTOR,
                enforce_detection=True,
            )
        except ValueError:
            os.unlink(temp.name)
            return jsonify({
                "status": "error",
                "message": "No face detected. Retake in good light, face the camera."
            }), 422

        person_folder = os.path.join(DB_PATH, person_name)
        os.makedirs(person_folder, exist_ok=True)
        filepath = os.path.join(person_folder, f"{uuid.uuid4().hex}.jpg")
        shutil.move(temp.name, filepath)

        return jsonify({"status": "success",
                        "message": f"Face registered for {person_name}"})

    except Exception as e:
        if os.path.exists(temp.name):
            os.unlink(temp.name)
        return jsonify({"status": "error", "message": str(e)}), 500


# ===================== 2. FACE RECOGNIZE (Emergency) =====================
@app.route("/recognize", methods=["POST"])
def recognize():
    if "image" not in request.files:
        return jsonify({"status": "error", "message": "No image"}), 400

    temp = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg")
    request.files["image"].save(temp.name)
    temp.close()

    try:
        normalize_image(temp.name)

        has_images = any(
            f.lower().endswith((".jpg", ".jpeg", ".png"))
            for _, _, files in os.walk(DB_PATH) for f in files
        )
        if not has_images:
            print("[recognize] FACE DATABASE IS EMPTY:", DB_PATH)
            return jsonify({"status": "error",
                            "message": "The face database on the server is EMPTY (no registered faces). "
                                       "Start the server from the DeepFaceServer folder that contains the "
                                       "'faces' folder, or register the faces again."}), 200

        # threshold=1.0 -> return the nearest photos always; we decide with our own THRESHOLD
        try:
            result = DeepFace.find(
                img_path=temp.name,
                db_path=DB_PATH,
                model_name=MODEL,
                detector_backend=DETECTOR,
                enforce_detection=True,
                silent=True,
                threshold=1.0,
            )
        except TypeError:
            # very old DeepFace without the threshold argument
            result = DeepFace.find(
                img_path=temp.name,
                db_path=DB_PATH,
                model_name=MODEL,
                detector_backend=DETECTOR,
                enforce_detection=True,
                silent=True,
            )

        best = best_match(result)
        if best is not None and best["distance"] <= THRESHOLD:
            person_name = os.path.basename(os.path.dirname(best["identity"]))
            print(f"[recognize] MATCH {person_name} distance={best['distance']:.3f} (limit {THRESHOLD})")
            return jsonify({"status": "success", "person": person_name,
                            "distance": best["distance"]})

        closest = best["distance"] if best is not None else None
        who = os.path.basename(os.path.dirname(best["identity"])) if best is not None else None
        print(f"[recognize] NO MATCH. closest={who} distance={closest} (limit {THRESHOLD})")
        return jsonify({"status": "not_found", "person": None, "closest": closest,
                        "closest_person": who, "limit": THRESHOLD})

    except ValueError as e:
        # Mukhaya hoyaganna ba
        print("[recognize] no face detected:", e)
        return jsonify({"status": "error",
                        "message": "No face detected. Move closer, good light."}), 200
    except Exception as e:
        print("[recognize] ERROR:", repr(e))
        return jsonify({"status": "error", "message": str(e)}), 500
    finally:
        if os.path.exists(temp.name):
            os.unlink(temp.name)


# ===================== 3. GET PROFILE PHOTO =====================
@app.route("/photo/<person_name>")
def get_photo(person_name):
    person_folder = os.path.join(DB_PATH, person_name)
    if not os.path.exists(person_folder):
        return jsonify({"error": "Person not found"}), 404

    images = (glob.glob(os.path.join(person_folder, "*.jpg")) +
              glob.glob(os.path.join(person_folder, "*.jpeg")) +
              glob.glob(os.path.join(person_folder, "*.png")))
    if not images:
        return jsonify({"error": "No image found"}), 404

    return send_from_directory(person_folder, os.path.basename(images[0]))


def db_summary():
    people = [d for d in os.listdir(DB_PATH) if os.path.isdir(os.path.join(DB_PATH, d))]
    photos = sum(
        1 for _, _, files in os.walk(DB_PATH) for f in files
        if f.lower().endswith((".jpg", ".jpeg", ".png")))
    return people, photos


@app.route("/")
def home():
    people, photos = db_summary()
    return f"DeepFace Server Running! Registered people: {len(people)}, photos: {photos}"


if __name__ == "__main__":
    _people, _photos = db_summary()
    print("=" * 60)
    print("Face database folder:", DB_PATH)
    print(f"Registered people: {len(_people)}  |  photos: {_photos}")
    for _p in _people:
        print("   -", _p)
    if _photos == 0:
        print("WARNING: the face database is EMPTY -> every scan will fail. "
              "Copy your old 'faces' folder here or register the faces again.")
    print("=" * 60)

    def preload():
        try:
            print("Loading models...")
            DeepFace.build_model(MODEL)
            print("Model loaded.")
        except Exception as e:
            print("Model preload failed:", e)

    threading.Thread(target=preload, daemon=True).start()
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)