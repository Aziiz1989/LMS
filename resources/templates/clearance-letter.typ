#let data = json("data.json")

#set page(
  paper: "a4",
  margin: (top: 2.5cm, bottom: 2.5cm, left: 2.5cm, right: 2.5cm)
)
#set text(size: 11pt, lang: "ar", dir: rtl)
#set par(justify: true)

// Header
#align(center)[
  #text(size: 18pt, weight: "bold")[
    خطاب تسوية \ Settlement Clearance Letter
  ]
]

#v(1.5cm)

// Date
#align(right)[
  #text[التاريخ / Date: #data.settlement-date]
]

#v(1cm)

// Contract Information
#grid(
  columns: (1fr, 1fr),
  row-gutter: 0.8cm,
  column-gutter: 1cm,

  [*رقم العقد / Contract:*], [#data.contract.external-id],
  [*العميل / Customer:*], [#data.contract.customer-name],
  [*تاريخ التسوية / Settlement Date:*], [#data.settlement-date],
)

#v(1.5cm)

// Settlement Breakdown
#align(center)[
  #text(size: 14pt, weight: "bold")[
    تفاصيل التسوية \ Settlement Breakdown
  ]
]

#v(0.5cm)

#table(
  columns: (auto, auto, auto),
  align: (left, right, right),
  stroke: 0.75pt,
  fill: (col, row) => if row == 0 { rgb("#f0f0f0") } else { white },

  [*البيان / Description*], [*المبلغ / Amount*], [*SAR*],

  [رأس المال المستحق / Outstanding Principal],
  [#data.outstanding-principal],
  [],

  [الربح المستحق / Accrued Profit],
  [#data.effective-accrued-unpaid-profit],
  [],

  [الرسوم المستحقة / Outstanding Fees],
  [#data.outstanding-fees],
  [],

  [الغرامة / Penalty (#data.penalty-days days)],
  [#data.penalty-amount],
  [],

  table.hline(stroke: 1.5pt),

  [*الإجمالي الفرعي / Subtotal*],
  [*#calc.round((data.outstanding-principal + data.effective-accrued-unpaid-profit + data.outstanding-fees + data.penalty-amount), digits: 2)*],
  [],

  [ناقص: الرصيد الدائن / Less: Credit Balance],
  [#data.credit-balance],
  [],

  table.hline(stroke: 2pt),

  [*مبلغ التسوية النهائي / Final Settlement Amount*],
  [*#data.settlement-amount*],
  [*SAR*],
)

#v(2cm)

// Footer Notes
#block[
  #set text(size: 10pt)
  #set par(justify: true)

  *ملاحظات / Notes:*

  - هذا الخطاب ساري المفعول بتاريخ التسوية المذكور أعلاه
  - This clearance letter is valid as of the settlement date specified above

  - أي معاملات بعد تاريخ التسوية قد تؤثر على المبلغ النهائي
  - Any transactions after the settlement date may affect the final amount

  #if data.at("manual-override?", default: false) [
  - *تنويه:* تم تطبيق تعديل يدوي على الربح المستحق
  - *Note:* A manual override has been applied to the accrued profit calculation
  ]
]

#v(1cm)

#align(center)[
  #line(length: 40%)
  #v(0.3cm)
  #text(size: 9pt)[
    التوقيع والختم / Signature & Stamp
  ]
]
