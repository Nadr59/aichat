package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

/**

* يبني نافذة الذاكرة التي ستصل إلى النموذج الرئيسي.

* 

* المسؤولية الوحيدة:

* selectedIds → الذاكرة الأصلية → Memory Window

* 

* لا يقوم بتلخيص أو إعادة صياغة أو ترتيب جديد للمحتوى.
  */
  class MemoryWindowBuilder {
  
  fun build(
  selectedIds: List<Long>,
  candidates: List<MemoryItem>
  ): String {
  
   if (selectedIds.isEmpty() || candidates.isEmpty()) {
     return ""
 }

 val memoriesById =
     candidates.associateBy { it.id }

 return selectedIds
     .distinct()
     .mapNotNull { id ->
         memoriesById[id]
     }
     .joinToString("\n\n") { memory ->
         memory.content
     }
     .trim()
  
  }
  }
