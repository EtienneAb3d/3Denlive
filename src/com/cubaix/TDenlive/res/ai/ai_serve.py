# Resident mode of the generation scripts of 3Denlive (copied next to them by com.cubaix.TDenlive.ai.PythonAI).
#
# A script calls run(main), main(argv) doing one generation from its arguments, and loads its models with
# cached(key, make): the model stays in memory between two generations.
# Without --serve: one generation from the arguments of the command line (exit code 0 / 1).
# With --serve: the arguments of the command line are those of the model (folder, device...); each line of stdin
# is a request, a JSON list of the other arguments (prompt, duration, output...), run as main(model arguments +
# request arguments); its messages (STATUS, STEP, DONE, ERROR...) end with an END line. READY is written once at
# the start. The process ends with its stdin (3Denlive closed) or when it is killed (another model to load).
import gc
import json
import sys
import traceback

_CACHE = {}


class Stopped(Exception):
	"""Raised by a script asked to stop (its request ends with ERROR STOPPED, the models stay loaded)"""
	pass


def out(*aArgs):
	print(*aArgs, flush=True)


def cached(aKey, aMake):
	"""The object made by aMake() the first time aKey is asked (a model loaded once)"""
	if aKey not in _CACHE:
		_CACHE[aKey] = aMake()
	return _CACHE[aKey]


def _free():
	# After an out of memory error: the models are loaded again by the next request
	_CACHE.clear()
	gc.collect()
	try:
		import torch
		if torch.cuda.is_available():
			torch.cuda.empty_cache()
		elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
			torch.mps.empty_cache()
	except Exception:
		pass


def _one(aMain, aArgv):
	try:
		return aMain(aArgv) or 0
	except Stopped:
		out("ERROR STOPPED")
		return 1
	except Exception as e:
		traceback.print_exc()
		aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
		out("ERROR " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
		if aOom:
			_free()
		return 1


def run(aMain):
	aArgv = sys.argv[1:]
	if "--serve" not in aArgv:
		sys.exit(_one(aMain, aArgv))
	aBase = [a for a in aArgv if a != "--serve"]
	out("READY")
	while True:
		aLine = sys.stdin.readline()
		if not aLine:
			break
		aLine = aLine.strip()
		if not aLine:
			continue
		try:
			aReq = [str(a) for a in json.loads(aLine)]
		except Exception as e:
			out("ERROR Bad request: " + str(e))
			out("END")
			continue
		_one(aMain, aBase + aReq)
		out("END")
