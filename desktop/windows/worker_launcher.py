import sys
from ema_windows.uia_worker import main

if __name__ == "__main__":
    if "--self-test" in sys.argv:
        from pywinauto import Desktop
        Desktop(backend="uia")
        print("PASS: packaged UI Automation runtime imports and initializes")
    else:
        main()
