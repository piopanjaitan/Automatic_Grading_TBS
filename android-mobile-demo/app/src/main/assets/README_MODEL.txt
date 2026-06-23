Letakkan model TensorFlow Lite hasil export di folder ini dengan nama:

grading_tph_int8.tflite

Model sumber saat ini ada di root project:
best_Palm_oil_v12_Grading_tph_ridwan_p_panjaitan.pt

Contoh export:
yolo export model=best_Palm_oil_v12_Grading_tph_ridwan_p_panjaitan.pt format=tflite int8=True imgsz=640

Jika INT8 menurunkan akurasi, export FP16 dan ubah nama asset yang dibaca di TfliteDetector.

Catatan 2 Juni 2026:
File grading_tph_int8.tflite saat ini adalah hasil export dynamic-range quantized
dari Ultralytics. Input/output model tetap float32:
- input: 1 x 640 x 640 x 3
- output: 1 x 8 x 8400
