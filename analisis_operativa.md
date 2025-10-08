# Análisis de la operativa del 8/10/2025

## Resumen cronológico
- **10:36:25.399** – El SCO inicia la transacción normal `d0e57d66-6fb2-4363-9fb0-590e74f52248`. 【F:recorte_operativa.txt†L171-L171】
- **10:36:25.665** – Primer escaneo de *Raïm negre s/llavors safata 400g* (UPC 16196) con precio 2,29€. 【F:recorte_operativa.txt†L200-L202】
- **10:36:38.377** – Segundo escaneo del mismo artículo; se aplica la promoción 56981 (2x3€) dejando el precio unitario en 1,50€. 【F:recorte_operativa.txt†L233-L236】
- **10:36:48.651** y **10:36:59.079** – Se escanean dos bandejas de *Raïm blanc s/llavors safata 400g* (UPC 16197) con la misma promoción, quedando también a 1,50€. 【F:recorte_operativa.txt†L267-L301】
- **10:37:11.863** – Se añade *Nous Brasil AO 150g AMETLLER ORIGEN* (UPC 13694) por 4,99€. 【F:recorte_operativa.txt†L311-L334】
- **10:37:29.299 – 10:37:33.063** – Se lee la tarjeta de fidelización 2801001277945; la API responde, el POS marca el estado como correcto y recalcula los precios de las cinco líneas (1,41€ y 1,39€ para las uvas, 4,64€ para las nueces), dejando el total en 10,22€. 【F:recorte_operativa.txt†L335-L358】

## Estado tras el escaneo
En el último mensaje enviado al SCO (`Totals`) el saldo pendiente es 10,22€, con 5 artículos y 3,93€ de descuentos acumulados. 【F:recorte_operativa.txt†L353-L353】 Después de ello el POS emite cinco mensajes `ItemPriceChanged` confirmando los nuevos importes por fidelización, sin registrar errores ni excepciones. 【F:recorte_operativa.txt†L354-L358】

## Qué ocurre al pulsar "Omitir embolsado"
El log no muestra ningún mensaje recibido del SCO tras las actualizaciones de fidelización; el siguiente evento es el intento manual de validación del operador a las 10:38:04.719. 【F:recorte_operativa.txt†L359-L360】 Esto indica que, desde la perspectiva del servidor POS, no llega ninguna orden asociada al botón "Omitir embolsado" ni a otras acciones de la interfaz de cliente durante ese intervalo, por lo que el POS queda a la espera de nuevas instrucciones.

## Conclusión
La operativa queda bloqueada porque, tras aplicar los descuentos de fidelización, el SCO deja de enviar mensajes al POS: no se registra la acción de "Omitir embolsado" ni otra instrucción posterior, motivo por el cual la máquina aparenta quedar congelada. El sistema POS sigue operativo y envía los totales y cambios de precio correctamente; el siguiente paso sería revisar el lado del SCO (UI / controlador de la balanza) para saber por qué no emite la orden correspondiente tras el recalculo de fidelización.
