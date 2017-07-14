
fun main(args: Array<String>) {
  println("before loop")
  while (args.size > 0) {
    println("before inner loop")
    while (args.size <= 0) {
      println("in inner loop")
      break
    }
    println("after inner loop")
  }
  println("bye")
}