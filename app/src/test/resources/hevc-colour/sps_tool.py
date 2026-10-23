"""H.265 SPS fixture tool (ROADMAP DV2.4), on `name=hex` lines (2-byte NAL header, EPBs as coded).

  python3 -I sps_tool.py branches < file   prints the SPS layout branches each SPS takes
  python3 -I sps_tool.py build < nals.txt  prints the hand-built SPS vectors of sps_more.txt

The walk follows ITU-T H.265 7.3.2.2 up to the VUI colour description. It is independent of
HevcVuiColour.kt and Media3, so it can say which branches a vector exercises.
"""
import sys


def unescape(data):
    out, zeros = bytearray(), 0
    for b in data:
        if zeros >= 2 and b == 3:
            zeros = 0
            continue
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def escape(rbsp):
    out, zeros = bytearray(), 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3)
            zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


class Bits:
    def __init__(self, rbsp):
        self.d, self.p = rbsp, 0

    def u(self, n):
        v = 0
        for _ in range(n):
            if self.p >= len(self.d) * 8:
                raise EOFError("SPS ends early")
            v = (v << 1) | ((self.d[self.p >> 3] >> (7 - (self.p & 7))) & 1)
            self.p += 1
        return v

    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
        return (1 << z) - 1 + self.u(z)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


def walk(nal):
    """Returns (branches, bit positions in the RBSP after the 2-byte header)."""
    br, pos = {}, {}
    b = Bits(unescape(nal[2:]))
    b.u(4)
    msl = b.u(3)
    br["max_sub_layers_minus1"] = msl
    b.u(1)
    b.u(3)
    br["profile"] = b.u(5)
    b.u(32 + 48 + 8)
    pos["sub_layer_flags"] = b.p
    flags = [(b.u(1), b.u(1)) for _ in range(msl)]
    if msl > 0:
        b.u(2 * (8 - msl))
    pos["sub_layer_data"] = b.p
    for profile_present, level_present in flags:
        if profile_present:
            b.u(88)
            br["sub_layer_profile_present"] = 1
        if level_present:
            b.u(8)
            br["sub_layer_level_present"] = 1
    b.ue()
    chroma = b.ue()
    br["chroma_format_idc"] = chroma
    if chroma == 3:
        br["separate_colour_plane_flag"] = b.u(1)
    br["size"] = (b.ue(), b.ue())
    if b.u(1):
        br["conformance_window"] = [b.ue() for _ in range(4)]
    br["bit_depth"] = (b.ue() + 8, b.ue() + 8)
    log2_max_poc_lsb = b.ue() + 4
    ordering_info = b.u(1)
    br["sub_layer_ordering_info_present"] = ordering_info
    for _ in range(0 if ordering_info else msl, msl + 1):
        b.ue(), b.ue(), b.ue()
    for _ in range(6):
        b.ue()
    if b.u(1):
        br["scaling_list_enabled"] = 1
        if b.u(1):
            br["scaling_list_data"] = {"pred": 0, "coded": 0}
            for size_id in range(4):
                matrix_id = 0
                while matrix_id < 6:
                    if not b.u(1):
                        b.ue()
                        br["scaling_list_data"]["pred"] += 1
                    else:
                        br["scaling_list_data"]["coded"] += 1
                        if size_id > 1:
                            b.se()
                        for _ in range(min(64, 1 << (4 + (size_id << 1)))):
                            b.se()
                    matrix_id += 3 if size_id == 3 else 1
    b.u(2)
    if b.u(1):
        br["pcm"] = 1
        b.u(8), b.ue(), b.ue(), b.u(1)
    count = b.ue()
    br["short_term_ref_pic_sets"] = count
    predicted = 0
    s0, s1 = [], []
    for idx in range(count):
        if idx != 0 and b.u(1):
            predicted += 1
            sign, abs_delta = b.u(1), b.ue() + 1
            delta = (1 - 2 * sign) * abs_delta
            n_neg, n_pos = len(s0), len(s1)
            use = []
            for _ in range(n_neg + n_pos + 1):
                use.append(1 if b.u(1) else b.u(1))
            neg, pos_ = [], []
            for j in range(n_pos - 1, -1, -1):
                d = s1[j] + delta
                if d < 0 and use[n_neg + j]:
                    neg.append(d)
            if delta < 0 and use[n_neg + n_pos]:
                neg.append(delta)
            for j in range(n_neg):
                d = s0[j] + delta
                if d < 0 and use[j]:
                    neg.append(d)
            for j in range(n_neg - 1, -1, -1):
                d = s0[j] + delta
                if d > 0 and use[j]:
                    pos_.append(d)
            if delta > 0 and use[n_neg + n_pos]:
                pos_.append(delta)
            for j in range(n_pos):
                d = s1[j] + delta
                if d > 0 and use[n_neg + j]:
                    pos_.append(d)
            s0, s1 = neg, pos_
        else:
            n_neg, n_pos = b.ue(), b.ue()
            s0, s1, acc = [], [], 0
            for _ in range(n_neg):
                acc -= b.ue() + 1
                s0.append(acc)
                b.u(1)
            acc = 0
            for _ in range(n_pos):
                acc += b.ue() + 1
                s1.append(acc)
                b.u(1)
    br["inter_rps_predicted_sets"] = predicted
    pos["long_term_flag"] = b.p
    pos["log2_max_poc_lsb"] = log2_max_poc_lsb
    if b.u(1):
        k = b.ue()
        br["long_term_ref_pics_sps"] = k
        b.u(k * (log2_max_poc_lsb + 1))
    b.u(2)
    if b.u(1):
        if b.u(1):
            idc = b.u(8)
            br["aspect_ratio_idc"] = idc
            if idc == 255:
                br["sar"] = (b.u(16), b.u(16))
        if b.u(1):
            b.u(1)
        if b.u(1):
            b.u(3)
            br["full_range"] = b.u(1)
            if b.u(1):
                br["colour"] = (b.u(8), b.u(8), b.u(8))
    return br, pos


def to_bits(rbsp):
    return "".join(f"{x:08b}" for x in rbsp)


def from_bits(bits):
    """Ends the payload (its old stop bit already cut) with rbsp_trailing_bits."""
    bits += "1"
    bits += "0" * (-len(bits) % 8)
    return bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits), 8))


def payload_bits(nal):
    bits = to_bits(unescape(nal[2:]))
    return bits[:bits.rindex("1")]  # drop rbsp_stop_one_bit and the alignment zeros


def ue_bits(v):
    code = f"{v + 1:b}"
    return "0" * (len(code) - 1) + code


def with_long_term_refs(nal, entries):
    """Sets long_term_ref_pics_present_flag and codes [(lt_ref_pic_poc_lsb_sps, used_by_curr_pic)]."""
    _, pos = walk(nal)
    bits = payload_bits(nal)
    p = pos["long_term_flag"]
    assert bits[p] == "0"
    lsb_bits = pos["log2_max_poc_lsb"]
    lt = "1" + ue_bits(len(entries)) + "".join(f"{lsb:0{lsb_bits}b}{used}" for lsb, used in entries)
    return nal[:2] + escape(from_bits(bits[:p] + lt + bits[p + 1:]))


def with_sub_layer_ptl(nal, profile_bits, level_idc):
    """Sets sub_layer_profile_present_flag and sub_layer_level_present_flag of every sub-layer."""
    br, pos = walk(nal)
    msl = br["max_sub_layers_minus1"]
    assert msl > 0
    bits = payload_bits(nal)
    p, q = pos["sub_layer_flags"], pos["sub_layer_data"]
    assert bits[p:p + 2 * msl] == "00" * msl
    data = (profile_bits + f"{level_idc:08b}") * msl
    return nal[:2] + escape(from_bits(bits[:p] + "11" * msl + bits[p + 2 * msl:q] + data + bits[q:]))


def read_lines(stream):
    for line in stream:
        line = line.strip()
        if line and not line.startswith("#"):
            name, hexed = line.split("=", 1)
            yield name, bytes.fromhex(hexed)


def build(nals):
    hlg = nals["hlg_tagged.sps"]
    sub = nals["hlg_sublayers.sps"]
    # Main 10 (profile_space 0, tier 0, idc 2, compatibility flags 1 and 2), progressive, frame-only,
    # then the 43 reserved bits and inbld/reserved flag; level 3 (90).
    profile = "00" + "0" + "00010" + f"{0x60000000:032b}" + "1001" + "0" * 44
    assert len(profile) == 88
    print("# hand_long_term_refs: hlg_tagged.sps with long_term_ref_pics_present_flag 1, "
          "num_long_term_ref_pics_sps 2: (lsb 5, used 1), (lsb 9, used 0)")
    print("hand_long_term_refs=" + with_long_term_refs(hlg, [(5, 1), (9, 0)]).hex())
    print("# hand_sub_layer_ptl: hlg_sublayers.sps with sub_layer_profile_present_flag and "
          "sub_layer_level_present_flag 1 for each sub-layer: Main 10, level 90")
    print("hand_sub_layer_ptl=" + with_sub_layer_ptl(sub, profile, 90).hex())


def main():
    if sys.argv[1] == "branches":
        for name, nal in read_lines(sys.stdin):
            if (nal[0] >> 1) & 0x3F != 33:
                continue
            try:
                print(name, walk(nal)[0])
            except EOFError as e:
                print(name, "unreadable:", e)
    elif sys.argv[1] == "build":
        build(dict(read_lines(sys.stdin)))


if __name__ == "__main__":
    main()
