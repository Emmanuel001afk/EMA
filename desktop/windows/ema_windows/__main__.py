import sys


def main():
    if "--self-test" in sys.argv:
        import tkinter
        import sounddevice
        from vosk import Model
        from pycaw.pycaw import AudioUtilities
        from .speech import resource_root
        model = Model(str(resource_root() / "model"))
        sounddevice.query_hostapis()
        del model
        return
    if "--uia-worker" in sys.argv:
        from .uia_worker import main as run
    else:
        from .app import main as run
    run()


if __name__ == "__main__":
    main()
