"""
Where a line breakpoint of the IDE stops in async code; loaded into CodeLLDB by `command script import`.

LLDB keeps one location of a file:line breakpoint per function (per inlined block). The function of the state machine of an async block
has its own shape: the code of one line lies in several pieces (the first pass, the resumption after an `.await`), and the PDB of an
MSVC build marks the jumps of the state dispatch with lines of the block. So LLDB stops in a piece that never runs (seen live: no stop
on `let x = f().await` in `#[tokio::main]`), in the dispatch (the first line of the block on every poll), or twice on one line.

    rust-line-locations <out file> <id>:<line> ...

For each breakpoint of CodeLLDB, in the functions of async blocks where LLDB has put it: the locations that only route to the line (a
jump of the dispatch) or repeat a piece are disabled, and `<id> <load address>` lines are written into the file for the pieces left
without a location; the IDE sets those as instruction breakpoints. LLDB breakpoints of its own made here would stop, but CodeLLDB does
not resume the program from them (seen live), and the protocol knows nothing of them. Nothing is done before the program is loaded.
"""
import shlex

import lldb


class _Piece:
    """A run of line-table entries of the line: code inlined from other files inside it does not end it."""

    def __init__(self, start):
        self.start = start  # SBAddress
        self.ranges = []  # [file start, file end) of its entries

    def contains(self, file_address):
        return any(lo <= file_address < hi for lo, hi in self.ranges)


def _pieces(function, cu, file, line):
    start = function.GetStartAddress().GetFileAddress()
    end = function.GetEndAddress().GetFileAddress()
    pieces, inside = [], False
    for i in range(cu.GetNumLineEntries()):
        e = cu.GetLineEntryAtIndex(i)
        at = e.GetStartAddress().GetFileAddress()
        if not start <= at < end or e.GetFileSpec() != file:
            continue
        if e.GetLine() == line:
            if not inside:
                inside = True
                pieces.append(_Piece(e.GetStartAddress()))
            pieces[-1].ranges.append((at, e.GetEndAddress().GetFileAddress()))
        else:
            inside = False
    return pieces


def _routes(target, piece, pieces):
    """The piece only leads to the line: it has the jump table of the dispatch, or it ends by jumping into another piece of the line."""
    instructions = []
    for lo, hi in piece.ranges:
        address = target.ResolveFileAddress(lo)
        for instruction in target.ReadInstructions(address, max(1, hi - lo)):
            if instruction.GetAddress().GetFileAddress() >= hi:
                break
            instructions.append(instruction)
    for instruction in instructions:
        if instruction.GetMnemonic(target).startswith("jmp") and instruction.GetOperands(target).startswith("*"):
            return True
    if instructions and instructions[-1].GetMnemonic(target).startswith("jmp"):
        operand = instructions[-1].GetOperands(target)
        try:
            to = target.ResolveLoadAddress(int(operand, 16)).GetFileAddress()
        except ValueError:
            return False
        return any(other is not piece and other.contains(to) for other in pieces)
    return False


def _adjust(target, breakpoint, line):
    """Disables the locations that route or repeat; the load addresses of the pieces left without a location."""
    by_function = {}
    for i in range(breakpoint.GetNumLocations()):
        location = breakpoint.GetLocationAtIndex(i)
        address = location.GetAddress()
        function = address.GetFunction()
        # only the bodies of async blocks and functions (and closures): `{closure#0}` in the PDB. Elsewhere the pieces of one line are
        # code a macro has put there, like the whole of `#[tokio::main]` on the last line of `main`, which would stop again and again
        if function.IsValid() and "{closure" in (function.GetName() or ""):
            by_function.setdefault(function.GetStartAddress().GetFileAddress(), []).append(location)
    extra = []
    for locations in by_function.values():
        address = locations[0].GetAddress()
        entry = address.GetLineEntry()
        if not entry.IsValid():
            continue
        pieces = _pieces(address.GetFunction(), address.GetCompileUnit(), entry.GetFileSpec(), line)
        routing = [piece for piece in pieces if _routes(target, piece, pieces)]
        stopping = set()
        for location in locations:
            at = location.GetAddress().GetFileAddress()
            piece = next((p for p in pieces if p.contains(at)), None)
            if piece is None:
                continue
            if piece in routing or piece in stopping:
                location.SetEnabled(False)
            else:
                location.SetEnabled(True)
                stopping.add(piece)
        for piece in pieces:
            if piece not in routing and piece not in stopping:
                load = piece.start.GetLoadAddress(target)
                if load != lldb.LLDB_INVALID_ADDRESS:
                    extra.append(load)
    return extra


def _safe_enum_summary(original):
    """`MSVCEnumSummaryProvider` of the toolchain fails on an enum without the names of its variants, like the state of an async block
    (seen live: "IndexError: list index out of range" in the console at every stop in `#[tokio::main]`); the name of the variant then."""

    def summary(valobj, _dict):
        try:
            return original(valobj, _dict)
        except Exception:
            try:
                variant = valobj.GetNonSyntheticValue().GetChildAtIndex(0)
                value = variant.GetChildMemberWithName("value")  # `VariantN { value: <the state>, .. }` of `enum2$`
                return (value if value.IsValid() else variant).GetTypeName().split("::")[-1]
            except Exception:
                return ""

    summary.__name__ = original.__name__
    summary.rust_line_locations_safe = True
    return summary


def _patch_formatters():
    """LLDB looks the summary function up by its name in `lldb_lookup` at each call, so the module attribute is what is replaced."""
    import sys

    module = sys.modules.get("lldb_lookup")
    original = getattr(module, "MSVCEnumSummaryProvider", None)
    if original is not None and not getattr(original, "rust_line_locations_safe", False):
        module.MSVCEnumSummaryProvider = _safe_enum_summary(original)


def line_locations(debugger, command, result, _dict):
    _patch_formatters()
    words = shlex.split(command)
    if not words:
        return
    out, pairs = words[0], words[1:]
    target = debugger.GetSelectedTarget()
    lines = []
    if target.IsValid() and target.GetProcess().IsValid():
        for pair in pairs:
            bp_id, line = (int(x) for x in pair.split(":"))
            breakpoint = target.FindBreakpointByID(bp_id)
            if breakpoint.IsValid():
                lines += ["%d %#x" % (bp_id, load) for load in sorted(set(_adjust(target, breakpoint, line)))]
    with open(out, "w") as f:
        f.write("\n".join(lines))


def __lldb_init_module(debugger, _dict):
    _patch_formatters()
    debugger.HandleCommand("command script add -o -f %s.line_locations rust-line-locations" % __name__)
