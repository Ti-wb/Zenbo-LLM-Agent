package contract

import (
	"encoding/binary"
	"fmt"
)

func ValidateWAV(audio []byte, declaredDurationMS int) error {
	if len(audio) < 44 {
		return fmt.Errorf("WAV audio must contain at least 44 bytes")
	}
	if len(audio) > 2*1024*1024 {
		return fmt.Errorf("WAV audio exceeds 2 MiB")
	}
	if string(audio[0:4]) != "RIFF" || string(audio[8:12]) != "WAVE" {
		return fmt.Errorf("audio is not a RIFF/WAVE file")
	}
	if int64(binary.LittleEndian.Uint32(audio[4:8]))+8 > int64(len(audio)) {
		return fmt.Errorf("WAV RIFF length exceeds the uploaded file")
	}

	var (
		format        uint16
		channels      uint16
		sampleRate    uint32
		bitsPerSample uint16
		dataBytes     uint32
		foundFormat   bool
		foundData     bool
	)
	for offset := 12; offset+8 <= len(audio); {
		size := uint64(binary.LittleEndian.Uint32(audio[offset+4 : offset+8]))
		end := uint64(offset) + 8 + size
		if end > uint64(len(audio)) {
			return fmt.Errorf("WAV chunk exceeds the uploaded file")
		}
		switch string(audio[offset : offset+4]) {
		case "fmt ":
			if size < 16 {
				return fmt.Errorf("WAV fmt chunk is incomplete")
			}
			format = binary.LittleEndian.Uint16(audio[offset+8 : offset+10])
			channels = binary.LittleEndian.Uint16(audio[offset+10 : offset+12])
			sampleRate = binary.LittleEndian.Uint32(audio[offset+12 : offset+16])
			bitsPerSample = binary.LittleEndian.Uint16(audio[offset+22 : offset+24])
			foundFormat = true
		case "data":
			if foundData {
				return fmt.Errorf("WAV contains duplicate data chunks")
			}
			dataBytes = uint32(size)
			foundData = true
		}
		offset = int(end + (size & 1))
	}
	if !foundFormat || !foundData || format != 1 || channels != 1 || sampleRate != 16_000 ||
		bitsPerSample != 16 || dataBytes == 0 {
		return fmt.Errorf("WAV must be PCM16, 16 kHz, mono audio")
	}
	computedDurationMS := int64(dataBytes) * 1000 / (int64(sampleRate) * int64(channels) * int64(bitsPerSample/8))
	if computedDurationMS < 1 || computedDurationMS > 30_000 {
		return fmt.Errorf("WAV duration must be between 1 and 30000 ms")
	}
	difference := computedDurationMS - int64(declaredDurationMS)
	if difference < 0 {
		difference = -difference
	}
	if declaredDurationMS < 1 || declaredDurationMS > 30_000 || difference > 500 {
		return fmt.Errorf("durationMs does not match the WAV payload")
	}
	return nil
}
