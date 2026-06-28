query GetGreetingByName {
  greeting(name: "Test") {
    name
    enumTest
    greeter {
      name
    }
  }
}



query AllGreetings {
 greetings
  name 
  greeter
  {
     name
   }
   }
   }
  