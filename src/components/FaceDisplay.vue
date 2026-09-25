<script setup>
import { computed } from 'vue';
import EyeFace from './EyeFace.vue';
import PixelFace from './PixelFace.vue';
import { FACE_VARIANTS, normalizeFaceVariant } from './faceVariants.js';

const props = defineProps({
  variant: { type: String, default: FACE_VARIANTS.EYES },
  emotion: { type: String, default: 'NEUTRAL' },
  mouthLevel: { type: Number, default: 0 },
  sleeping: { type: Boolean, default: false },
  turnState: { type: String, default: 'IDLE' },
});

const renderer = computed(() =>
  normalizeFaceVariant(props.variant) === FACE_VARIANTS.CLASSIC ? PixelFace : EyeFace,
);
</script>

<template>
  <component
    :is="renderer"
    :emotion="emotion"
    :mouth-level="mouthLevel"
    :sleeping="sleeping"
    :turn-state="turnState"
  />
</template>
