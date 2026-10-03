import os
import sys

if __package__ in (None, ""):
    # `python scripts/apikit ...` — directory execution, no package context.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import main as _m
else:
    from . import main as _m

sys.exit(_m.main())
