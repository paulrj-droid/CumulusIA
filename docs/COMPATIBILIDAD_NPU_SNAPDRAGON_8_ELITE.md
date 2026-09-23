# NPU Snapdragon 8 Elite (SM8750)

Esta configuración prepara Cumulus IA para ejecutar modelos LiteRT-LM en el
Hexagon Tensor Processor del Snapdragon 8 Elite, utilizado por el Samsung
Galaxy S25.[span_11](start_span)[span_11](end_span)

## Componentes incluidos

- `com.google.ai.edge.litertlm:litertlm-android:0.17.0`[span_12](start_span)[span_12](end_span)
- `com.qualcomm.qti:qnn-runtime:2.42.0`[span_13](start_span)[span_13](end_span)
- `com.qualcomm.qti:qnn-litert-delegate:2.42.0`[span_14](start_span)[span_14](end_span)
- `src/main/jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so`[span_15](start_span)[span_15](end_span)
- ABI Android: `arm64-v8a`[span_16](start_span)[span_16](end_span)

El runtime QNN 2.42 aporta `libQnnHtp.so`, `libQnnSystem.so`,
`libQnnHtpPrepare.so` y los binarios HTP V79 requeridos por SM8750.[span_17](start_span)[span_17](end_span)

## Modelo correcto

Los modelos GGUF siguen usando Vulkan o CPU. Para NPU debe utilizarse un
modelo `.litertlm` preparado para SM8750. Google publica este modelo de
referencia:[span_18](start_span)[span_18](end_span)

https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm[span_19](start_span)[span_19](end_span)

Hugging Face puede exigir iniciar sesión y aceptar la licencia del modelo.[span_20](start_span)[span_20](end_span)

## Verificación

En la pantalla de capacidades, el APK debe mostrar:[span_21](start_span)[span_21](end_span)

`NPU Snapdragon 8 Elite (SM8750): HTP V79 preparado`[span_22](start_span)[span_22](end_span)

Después de cargar el modelo con backend NPU, las respuestas deben mostrar:[span_23](start_span)[span_23](end_span)

`NPU Snapdragon 8 Elite HTP (LiteRT-LM)`[span_24](start_span)[span_24](end_span)

Si la inicialización HTP falla, Cumulus IA lo registra y utiliza GPU o CPU. No
etiqueta el fallback como NPU.[span_25](start_span)[span_25](end_span)

## Procedencia de Dispatch

`libLiteRtDispatch_Qualcomm.so` procede del ejemplo Android Apache-2.0:[span_26](start_span)[span_26](end_span)

https://github.com/carrycooldude/ModelGarden-QNN-LiteRT[span_27](start_span)[span_27](end_span)

SHA-256 del binario incorporado:[span_28](start_span)[span_28](end_span)

`b759185b9026ca917c121a2aa5a17439d09c2fb2fb046135b9a763987e11c157`[span_29](start_span)[span_29](end_span)

La integración sigue la guía oficial de Google para LiteRT-LM Qualcomm:[span_30](start_span)[span_30](end_span)

https://developers.google.com/edge/litert/next/litert_lm_npu[span_31](start_span)[span_31](end_span)
