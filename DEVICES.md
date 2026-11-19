# Tested phones

The app reads `devices.json` (same folder as this file) to know which phones the project has seen run. This table is the
same list in words.

| Phone | SoC | GPU | iOS 26 | iOS 16 | watchOS | Note |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S938B (Galaxy S25 Ultra) | SM8750 | Adreno 830 | works | works | works | Galaxy S25 Ultra |
| Xiaomi 25102PCBEG | SM8850 | Adreno 840 | works | not reported | not reported | One report: ran 10 minutes at the 2x screen, up to 46 fps. |
| HUAWEI LMR-LX9 | Kirin | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 25 fps; setup 4 h 18 min (first start 2 h 20 min). The app reads only "Kirin" as this phone's SoC, so the row covers every phone that reports it. |
| Xiaomi Pad 7 2410CRP4CG | SM7675 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 21 fps; setup 1 h 19 min (first start 37 min). |
| Samsung SM-A556B | s5e8845 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 12 fps, it lags; setup 5 h 35 min (disk build 4 h 22 min, first start 1 h 1 min). |
| Samsung SM-G990B2 | SM8350 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 37 fps. |
| Xiaomi 2511FPC34G | MT6899 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 46 fps. |
| Nothing A059 | SM7635 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 40 minutes, up to 16 fps; setup 1 h 53 min (disk build 54 min, first start 54 min). |
| Xiaomi Redmi K20 Pro | SM8150 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 13 fps; setup 3 h 9 min (disk build 1 h 43 min, first start 1 h 11 min); free memory went down to 208 MB. |
| Infinix X6885 | MT6789 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 35 minutes, up to 6 fps; setup 4 h 50 min (disk build 2 h 14 min, first start 1 h 54 min). |
| OPPO CPH2305 | SM8450 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 9 fps; setup 11 h 38 min (disk build 10 h 14 min on a hot phone, first start 1 h 3 min). |
| Samsung SM-S918B | SM8550 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 45 minutes, up to 21 fps; first start 29 min; free memory went down to 709 MB. |
| OPPO CPH2159 | SM7125 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 55 minutes, up to 34 fps. |
| Xiaomi 24040RN64Y | MT6769 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 2 h 25 min, up to 39 fps. |
| Vivo V2250 | SM7325 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 1 h 55 min, up to 11 fps; free memory went down to 661 MB. |
| Motorola moto g - 2026 | MT6835 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 15 minutes, up to 37 fps; free memory went down to 182 MB. |
| OPPO CPH2179 | mt6765 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 3 h 55 min, up to 36 fps, the reporter says it is very laggy and apps do not open by a tap; free memory went down to 333 MB. |
| Samsung SM-S928B | SM8650 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 24 fps; the phone reached thermal status severe. |
| Xiaomi 23122PCD1G | SM7435 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 9 fps; setup 3 h 6 min (disk build 1 h 39 min, first start 1 h 12 min). |
| Xiaomi 25062RN2DY | SM6225 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 9 fps; setup 4 h 15 min (disk build 2 h 25 min, first start 1 h 20 min); free memory went down to 466 MB. |
| Samsung SM-G975F | Exynos 9820 | not used by iOS 16 | not reported | works | not reported | One report: iOS 16 ran 10 minutes, up to 3 fps, very slow; setup 6 h 42 min (disk build 2 h 49 min, first start 3 h 39 min). |
| any phone with an ARM Mali GPU | any | Mali | fails | no GPU needed | fails | Most fail the GPU check (no VK_EXT_external_memory_host); one that passes runs, slowly so far. |
| any phone with an Imagination PowerVR GPU | any | PowerVR | fails | no GPU needed | fails | The GPU check fails (no VK_EXT_external_memory_host); not supported yet. |
| any phone with a Samsung Xclipse GPU | any | Xclipse | fails | no GPU needed | fails | The GPU check fails or the picture is wrong; not supported yet. |

A row with a SoC is about that chip. A row without one is a GPU family and covers every phone whose GPU name contains it.
iOS 16 does not use the GPU, so it is matched on the SoC alone.

How a phone gets onto the list: after a guest has run on a phone for at least ten minutes and the phone's chip is not on
the list as working, the app offers a "works" report, a prefilled GitHub issue that you read and submit yourself. The
issue is added here and then closed. A phone of a family listed as failing that does run still gets the offer, and its
report corrects the row.
