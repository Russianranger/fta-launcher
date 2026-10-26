"""Bounded, read-only PE import/export metadata for private component inspection.

This is a metadata reader, not an executable loader or authenticity check.
"""
import struct


class InvalidPE(ValueError):
    pass


def require(value, reason='invalid-pe-metadata'):
    if not value:
        raise InvalidPE(reason)


class PE:
    MAX_BYTES = 16 * 1024 * 1024

    def __init__(self, data):
        require(64 <= len(data) <= self.MAX_BYTES, 'pe-size-limit')
        self.data = data
        self.budget = 1024 * 1024
        require(self.at(0, 2) == b'MZ')
        pe = self.words(60, '<I')[0]
        require(pe >= 64 and self.at(pe, 4) == b'PE\0\0')
        machine, sections, _, _, _, optional_size, characteristics = self.words(pe + 4, '<HHIIIHH')
        require(1 <= sections <= 96 and 96 <= optional_size <= 4096)
        optional = self.at(pe + 24, optional_size)
        magic = struct.unpack_from('<H', optional)[0]
        self.architecture = {(0x14c, 0x10b): 'x86', (0x8664, 0x20b): 'x64'}.get((machine, magic))
        require(self.architecture is not None, 'unsupported-pe-architecture')
        self.dll = bool(characteristics & 0x2000)
        self.width = 4 if magic == 0x10b else 8
        start = 96 if self.width == 4 else 112
        require(len(optional) >= start)
        count = struct.unpack_from('<I', optional, start - 4)[0]
        require(count <= 16 and start + count * 8 <= len(optional))
        self.directories = [struct.unpack_from('<II', optional, start + i * 8) for i in range(count)]
        self.sections = []
        for i in range(sections):
            row = self.at(pe + 24 + optional_size + 40 * i, 40)
            _, rva, size, raw = struct.unpack_from('<IIII', row, 8)
            flags = struct.unpack_from('<I', row, 36)[0]
            require(rva + size <= 2**32 and raw + size <= len(data))
            self.sections.append((rva, size, raw, flags))

    def at(self, offset, count):
        require(0 <= offset <= len(self.data) and 0 <= count <= len(self.data) - offset)
        require(count <= self.budget, 'pe-read-budget')
        self.budget -= count
        return self.data[offset:offset + count]

    def words(self, offset, fmt):
        return struct.unpack(fmt, self.at(offset, struct.calcsize(fmt)))

    def mapping(self, rva, count):
        require(0 <= rva < 2**32 and count > 0 and rva + count <= 2**32)
        matches = [(raw + rva - address, flags) for address, size, raw, flags in self.sections
                   if address <= rva and rva - address + count <= size]
        require(len(matches) == 1, 'ambiguous-or-unmapped-rva')
        return matches[0]

    def rva(self, address, count):
        return self.at(self.mapping(address, count)[0], count)

    def string(self, address):
        value = bytearray()
        for i in range(257):
            byte = self.rva(address + i, 1)[0]
            if byte == 0:
                require(value, 'empty-pe-name')
                return value.decode('ascii')
            require(32 <= byte <= 126, 'invalid-pe-name')
            value.append(byte)
        raise InvalidPE('pe-name-limit')

    def directory(self, index, minimum):
        address, size = self.directories[index] if index < len(self.directories) else (0, 0)
        if (address, size) == (0, 0):
            return None
        require(address > 0 and minimum <= size <= self.MAX_BYTES)
        self.mapping(address, size)
        return address, size

    def imports(self):
        directory = self.directory(1, 20)
        if directory is None:
            return {}
        address, size = directory
        result = {}; symbols = 0
        for index in range(min(size // 20, 129)):
            entry = struct.unpack('<IIIII', self.rva(address + index * 20, 20))
            if not any(entry):
                return result
            require(index < 128, 'pe-import-limit')
            lookup, _, _, name, first = entry
            library = self.string(name).lower()
            require(library not in result, 'duplicate-import-library')
            require(lookup or first)
            table = lookup or first
            names = []
            for slot in range(8193):
                value = int.from_bytes(self.rva(table + slot * self.width, self.width), 'little')
                if value == 0:
                    break
                symbols += 1; require(symbols <= 8192, 'pe-import-limit')
                high = 1 << (self.width * 8 - 1)
                if value & high:
                    require(value & ~(high | 0xffff) == 0)
                    names.append(value & 0xffff)
                else:
                    require(value < 2**32)
                    self.rva(value, 2)  # IMAGE_IMPORT_BY_NAME hint
                    names.append(self.string(value + 2))
            else:
                raise InvalidPE('pe-import-limit')
            result[library] = names
        raise InvalidPE('unterminated-import-directory')

    def exports(self):
        directory = self.directory(0, 40)
        if directory is None:
            return {}
        address, size = directory
        row = self.rva(address, 40)
        functions, names, eat, name_table, ordinals = struct.unpack_from('<IIIII', row, 20)
        require(functions <= 8192 and names <= 4096 and names <= functions, 'pe-export-limit')
        if names == 0:
            return {}
        require(functions > 0)
        targets = struct.unpack('<' + 'I' * functions, self.rva(eat, functions * 4))
        strings = struct.unpack('<' + 'I' * names, self.rva(name_table, names * 4))
        indices = struct.unpack('<' + 'H' * names, self.rva(ordinals, names * 2))
        result = {}
        for string, index in zip(strings, indices):
            require(index < functions)
            name = self.string(string)
            require(name not in result, 'duplicate-export-name')
            target = targets[index]
            forwarded = address <= target < address + size
            executable = target != 0 and not forwarded and bool(self.mapping(target, 1)[1] & 0x20000000)
            if forwarded:
                self.string(target)
            result[name] = dict(forwarded=forwarded, executable=executable)
        return result
