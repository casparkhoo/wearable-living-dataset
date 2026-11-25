"""Download PaPaGei model weights from Zenodo."""
import os
import urllib.request
import tarfile

ZENODO_URL = "https://zenodo.org/records/13983110/files/papagei_s.pt"
WEIGHTS_DIR = "weights"
WEIGHTS_FILE = os.path.join(WEIGHTS_DIR, "papagei_s.pt")

def download_weights():
    os.makedirs(WEIGHTS_DIR, exist_ok=True)

    if os.path.exists(WEIGHTS_FILE):
        print(f"Weights already exist at {WEIGHTS_FILE}")
        return

    print(f"Downloading PaPaGei model weights from {ZENODO_URL}...")
    try:
        urllib.request.urlretrieve(ZENODO_URL, WEIGHTS_FILE)
        print(f"Downloaded to {WEIGHTS_FILE}")
    except Exception as e:
        print(f"Error downloading: {e}")
        print("Please manually download from https://zenodo.org/records/13983110")

if __name__ == "__main__":
    download_weights()
